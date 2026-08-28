package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.keep.db.db
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobFailed
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.endpoint.StaticEndpointResolver
import org.tekfive.relaykt.provider.ProviderException
import org.tekfive.relaykt.support.FakeJobContext
import org.tekfive.relaykt.support.TestDatabase
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.testing.InMemoryProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageQueueIntegrationTest {

    private val emailEndpoint = Endpoint("mem-email", InMemoryProvider.email.id, JsonObject())
    private val smsEndpoint = Endpoint("mem-sms", InMemoryProvider.sms.id, JsonObject())

    @BeforeAll
    fun startDatabase() {
        assumeTrue(TestDatabase.dockerAvailable, "Docker is required for queue integration tests")
        TestDatabase.ensureStarted()
    }

    @BeforeTest
    fun setUp() {
        TestDatabase.truncateAll()
        InMemoryProvider.clearAll()
        Relay.registerEndpointResolver(StaticEndpointResolver(emailEndpoint, smsEndpoint))
    }

    @AfterTest
    fun tearDown() {
        Relay.reset()
        InMemoryProvider.clearAll()
    }

    @Test
    fun `enqueued message is dispatched, sent, and tracked`() {
        val queuedMessageId = Relay.enqueue(
            TestMessages.email(cc = listOf(TestMessages.cc)),
            emailEndpoint,
            QueueOptions(label = "welcome", trackReceipt = true, maxAttempts = 2),
        )

        val queued = assertNotNull(MessageQueue.find(queuedMessageId))
        assertEquals(QueuedMessageState.QUEUED, queued.state)
        assertEquals(listOf("to@example.com", "cc@example.com"), queued.recipients)
        assertEquals("welcome", queued.label)

        assertEquals(1, MessageQueueProcessor.processOnce())
        assertEquals(QueuedMessageState.PENDING, MessageQueue.find(queuedMessageId)!!.state)
        assertTrue(db { JobRecordsTable.hasNonTerminatedJob(SendQueuedMessageJob.jobTypeIdentifier) })
        assertEquals(0, MessageQueueProcessor.processOnce(), "a PENDING message must not be dispatched twice")

        val result = runSendJob(queuedMessageId)

        assertIs<JobCompleted>(result)
        val sent = MessageQueue.find(queuedMessageId)!!
        assertEquals(QueuedMessageState.SENT, sent.state)
        assertEquals(1, sent.attemptCount)
        assertEquals("memory-email-1", sent.providerMessageId)
        assertEquals(1, InMemoryProvider.email.messages.size)

        val attempts = MessageQueue.attempts(queuedMessageId)
        assertEquals(1, attempts.size)
        assertEquals(DeliveryAttemptState.SENT, attempts.single().state)
        assertNotNull(attempts.single().endedAt)

        val receipts = MessageQueue.receipts(queuedMessageId)
        assertEquals(2, receipts.size)
        assertTrue(receipts.all { it.status == DeliveryReceiptStatus.WAITING && it.providerMessageId == "memory-email-1" })
        assertEquals("memory-email-1", receipts.first().details.string("providerMessageId"))
        assertEquals("SENT", receipts.first().details.string("lastDeliveryStatus"))
        assertEquals(setOf("to@example.com", "cc@example.com"), receipts.map { it.recipientAddress }.toSet())
    }

    @Test
    fun `receipt job resolves receipts from provider status`() {
        val queuedMessageId = Relay.enqueue(TestMessages.email(), emailEndpoint, QueueOptions(trackReceipt = true))
        MessageQueueProcessor.processOnce()
        runSendJob(queuedMessageId)

        // Provider still reports SENT: the receipt keeps waiting.
        runReceiptJob()
        var receipt = MessageQueue.receipts(queuedMessageId).single()
        assertEquals(DeliveryReceiptStatus.WAITING, receipt.status)
        assertEquals(DeliveryStatus.SENT, receipt.lastDeliveryStatus)
        assertNotNull(receipt.lastCheckedAt)

        InMemoryProvider.email.setStatus("memory-email-1", DeliveryStatus.OPENED)
        runReceiptJob()
        receipt = MessageQueue.receipts(queuedMessageId).single()
        assertEquals(DeliveryReceiptStatus.RECEIVED, receipt.status)
        assertEquals(DeliveryStatus.OPENED, receipt.lastDeliveryStatus)

        // A failed delivery is reported as such.
        val failedId = Relay.enqueue(TestMessages.email(), emailEndpoint, QueueOptions(trackReceipt = true))
        MessageQueueProcessor.processOnce()
        runSendJob(failedId)
        InMemoryProvider.email.setStatus("memory-email-2", DeliveryStatus.FAILED)
        runReceiptJob()
        assertEquals(DeliveryReceiptStatus.DELIVERY_FAILURE, MessageQueue.receipts(failedId).single().status)
    }

    @Test
    fun `receipts track per-recipient provider ids and resolve independently`() {
        val fanOut = object : org.tekfive.relaykt.provider.Provider<org.tekfive.relaykt.Message> {
            override val id = "fanout-team"
            override val channel = org.tekfive.relaykt.Channel.TEAM
            override val capabilities = setOf(org.tekfive.relaykt.Capability.MULTIPLE_RECIPIENTS, org.tekfive.relaykt.Capability.STATUS_LOOKUP)
            override fun send(message: org.tekfive.relaykt.Message, configuration: JsonObject): org.tekfive.relaykt.SendResult {
                val ids = message.to.associate { it.address to "id-${it.address}" }
                return org.tekfive.relaykt.SendResult("multi:" + ids.values.joinToString(","), id, recipientMessageIds = ids)
            }
            override fun status(messageId: String, configuration: JsonObject) =
                if (messageId == "id-a") DeliveryStatus.FAILED else DeliveryStatus.DELIVERED
        }
        org.tekfive.relaykt.provider.ProviderRegistry.register(fanOut)
        try {
            val endpoint = Endpoint("fanout", fanOut.id, JsonObject())
            Relay.registerEndpointResolver(StaticEndpointResolver(endpoint))
            val queuedMessageId = Relay.enqueue(
                TestMessages.team(to = listOf(org.tekfive.relaykt.MessageAddress("a"), org.tekfive.relaykt.MessageAddress("b"))),
                endpoint,
                QueueOptions(trackReceipt = true),
            )
            MessageQueueProcessor.processOnce()
            runSendJob(queuedMessageId)

            val receipts = MessageQueue.receipts(queuedMessageId).associateBy { it.recipientAddress }
            assertEquals("id-a", receipts.getValue("a").providerMessageId)
            assertEquals("id-b", receipts.getValue("b").providerMessageId)

            runReceiptJob()
            val updated = MessageQueue.receipts(queuedMessageId).associateBy { it.recipientAddress }
            assertEquals(DeliveryReceiptStatus.DELIVERY_FAILURE, updated.getValue("a").status)
            assertEquals(DeliveryReceiptStatus.RECEIVED, updated.getValue("b").status)
        } finally {
            org.tekfive.relaykt.provider.ProviderRegistry.reset()
        }
    }

    @Test
    fun `receipts time out after the configured wait`() {
        val queuedMessageId = Relay.enqueue(TestMessages.email(), emailEndpoint, QueueOptions(trackReceipt = true, maxReceiptWaitMinutes = 1))
        MessageQueueProcessor.processOnce()
        runSendJob(queuedMessageId)

        db {
            DeliveryReceiptTable.update({ DeliveryReceiptTable.queuedMessageId eq queuedMessageId }) {
                it[createdAt] = System.currentTimeMillis() - 2 * 60_000L
            }
        }
        runReceiptJob()

        assertEquals(DeliveryReceiptStatus.TIMED_OUT, MessageQueue.receipts(queuedMessageId).single().status)
    }

    @Test
    fun `recoverable failures are retried with backoff until attempts run out`() {
        val queuedMessageId = Relay.enqueue(TestMessages.sms(), smsEndpoint, QueueOptions(maxAttempts = 2))

        InMemoryProvider.sms.failNextSendWith(ProviderException("throttled", statusCode = 429))
        MessageQueueProcessor.processOnce()
        assertIs<JobFailed>(runSendJob(queuedMessageId))

        var queued = MessageQueue.find(queuedMessageId)!!
        assertEquals(QueuedMessageState.WAITING_TO_RETRY, queued.state)
        assertEquals(1, queued.attemptCount)
        assertNotNull(queued.nextAttemptAt)
        val failedAttempt = MessageQueue.attempts(queuedMessageId).single()
        assertEquals(DeliveryAttemptState.FAILED, failedAttempt.state)
        assertEquals(true, failedAttempt.recoverable)
        assertTrue(failedAttempt.details!!.contains("429"))

        // Retry delay is configured to 0 for tests, so the next poll dispatches it again.
        assertEquals(1, MessageQueueProcessor.processOnce())
        assertIs<JobCompleted>(runSendJob(queuedMessageId))
        queued = MessageQueue.find(queuedMessageId)!!
        assertEquals(QueuedMessageState.SENT, queued.state)
        assertEquals(2, queued.attemptCount)
        assertNull(queued.nextAttemptAt)
        assertEquals(2, MessageQueue.attempts(queuedMessageId).size)

        // Last allowed attempt failing recoverably ends in FAILED.
        val exhaustedId = Relay.enqueue(TestMessages.sms(), smsEndpoint, QueueOptions(maxAttempts = 1))
        InMemoryProvider.sms.failNextSendWith(ProviderException("down", statusCode = 503))
        MessageQueueProcessor.processOnce()
        runSendJob(exhaustedId)
        assertEquals(QueuedMessageState.FAILED, MessageQueue.find(exhaustedId)!!.state)
    }

    @Test
    fun `non-recoverable failures are not retried`() {
        val queuedMessageId = Relay.enqueue(TestMessages.sms(), smsEndpoint, QueueOptions(maxAttempts = 5))
        InMemoryProvider.sms.failNextSendWith(ProviderException("bad number", statusCode = 400))
        MessageQueueProcessor.processOnce()

        runSendJob(queuedMessageId)

        val queued = MessageQueue.find(queuedMessageId)!!
        assertEquals(QueuedMessageState.FAILED, queued.state)
        assertEquals(1, queued.attemptCount)
        assertEquals(false, MessageQueue.attempts(queuedMessageId).single().recoverable)
        assertEquals(0, MessageQueueProcessor.processOnce())
    }

    @Test
    fun `unresolvable endpoint fails the message permanently`() {
        val queuedMessageId = Relay.enqueue(TestMessages.sms(), smsEndpoint, QueueOptions(maxAttempts = 3))
        Relay.registerEndpointResolver(StaticEndpointResolver())
        MessageQueueProcessor.processOnce()

        assertIs<JobFailed>(runSendJob(queuedMessageId))

        assertEquals(QueuedMessageState.FAILED, MessageQueue.find(queuedMessageId)!!.state)
    }

    @Test
    fun `deferred messages wait and queued messages can be cancelled`() {
        val laterId = Relay.enqueue(TestMessages.sms(), smsEndpoint, QueueOptions(deliverAfter = System.currentTimeMillis() + 60_000L))
        val cancelId = Relay.enqueue(TestMessages.sms(), smsEndpoint)

        assertEquals(QueuedMessageState.QUEUED, MessageQueue.cancel(cancelId))
        assertEquals(QueuedMessageState.CANCELLED, MessageQueue.find(cancelId)!!.state)
        assertNull(MessageQueue.cancel(cancelId), "an already cancelled message reports null")
        assertEquals(0, MessageQueueProcessor.processOnce())
        assertEquals(QueuedMessageState.QUEUED, MessageQueue.find(laterId)!!.state)

        assertEquals(1, MessageQueueProcessor.processOnce(now = System.currentTimeMillis() + 120_000L))
        assertEquals(QueuedMessageState.PENDING, MessageQueue.find(laterId)!!.state)
        // A PENDING message can still be cancelled; its delivery job then declines to send.
        assertEquals(QueuedMessageState.PENDING, MessageQueue.cancel(laterId))
        assertIs<JobFailed>(runSendJob(laterId))
        assertEquals(QueuedMessageState.CANCELLED, MessageQueue.find(laterId)!!.state)
        assertTrue(MessageQueue.attempts(laterId).isEmpty())
        assertNull(MessageQueue.cancel(999_999L))
    }

    @Test
    fun `stalled messages are recovered by the processor`() {
        val pendingId = Relay.enqueue(TestMessages.sms(), smsEndpoint)
        val processingId = Relay.enqueue(TestMessages.sms(), smsEndpoint)
        MessageQueueProcessor.processOnce()
        val longAgo = System.currentTimeMillis() - 2L * 60 * 60_000L
        db {
            QueuedMessageTable.update({ QueuedMessageTable.id eq pendingId }) { it[lastStateChangeAt] = longAgo }
            QueuedMessageTable.update({ QueuedMessageTable.id eq processingId }) {
                it[state] = QueuedMessageState.PROCESSING
                it[lastStateChangeAt] = longAgo
            }
        }

        val acted = MessageQueueProcessor.processOnce()

        // The stalled PENDING message is re-queued and will be re-dispatched on the next pass.
        assertEquals(QueuedMessageState.QUEUED, MessageQueue.find(pendingId)!!.state)
        assertEquals(QueuedMessageState.TIMED_OUT, MessageQueue.find(processingId)!!.state)
        assertEquals(2, acted)
    }

    @Test
    fun `a message whose state changed externally while sending is not clobbered`() {
        val queuedMessageId = Relay.enqueue(TestMessages.sms(), smsEndpoint)
        MessageQueueProcessor.processOnce()
        val timingOutProvider = object : org.tekfive.relaykt.provider.Provider<org.tekfive.relaykt.Message> {
            override val id = "timeout-sms"
            override val channel = org.tekfive.relaykt.Channel.SMS
            override val capabilities = emptySet<org.tekfive.relaykt.Capability>()
            override fun send(message: org.tekfive.relaykt.Message, configuration: JsonObject): org.tekfive.relaykt.SendResult {
                db {
                    QueuedMessageTable.update({ QueuedMessageTable.id eq queuedMessageId }) { it[state] = QueuedMessageState.TIMED_OUT }
                }
                return org.tekfive.relaykt.SendResult("x", id)
            }
        }
        org.tekfive.relaykt.provider.ProviderRegistry.register(timingOutProvider)
        try {
            Relay.registerEndpointResolver(StaticEndpointResolver(Endpoint("mem-sms", timingOutProvider.id, JsonObject())))
            runSendJob(queuedMessageId)
            assertEquals(QueuedMessageState.TIMED_OUT, MessageQueue.find(queuedMessageId)!!.state)
        } finally {
            org.tekfive.relaykt.provider.ProviderRegistry.reset()
        }
    }

    @Test
    fun `cleanup job removes old completed messages by retention class`() {
        val sentId = Relay.enqueue(TestMessages.sms(), smsEndpoint)
        val failedId = Relay.enqueue(TestMessages.sms(), smsEndpoint)
        val recentId = Relay.enqueue(TestMessages.sms(), smsEndpoint)
        val longAgo = System.currentTimeMillis() - 400L * 24 * 60 * 60_000L
        db {
            QueuedMessageTable.update({ QueuedMessageTable.id eq sentId }) { it[state] = QueuedMessageState.SENT; it[lastStateChangeAt] = longAgo }
            QueuedMessageTable.update({ QueuedMessageTable.id eq failedId }) { it[state] = QueuedMessageState.FAILED; it[lastStateChangeAt] = longAgo }
            QueuedMessageTable.update({ QueuedMessageTable.id eq recentId }) { it[state] = QueuedMessageState.SENT }
        }

        val job = CleanQueuedMessagesJob()
        assertIs<JobCompleted>(job.execute(FakeJobContext(job, null)))

        assertNull(MessageQueue.find(sentId))
        assertNull(MessageQueue.find(failedId))
        assertNotNull(MessageQueue.find(recentId))
    }

    @Test
    fun `send failure classification reaches the attempt record`() {
        val queuedMessageId = Relay.enqueue(TestMessages.email(), emailEndpoint)
        InMemoryProvider.email.failNextSendWith(RelayException("custom recoverable", recoverable = true))
        MessageQueueProcessor.processOnce()
        runSendJob(queuedMessageId)
        assertEquals("custom recoverable", MessageQueue.attempts(queuedMessageId).single().details)
    }

    private fun runSendJob(queuedMessageId: Long) = SendQueuedMessageJob().let { job ->
        job.execute(FakeJobContext(job, json { SendQueuedMessageJob.QUEUED_MESSAGE_ID_PROPERTY set queuedMessageId }))
    }

    private fun runReceiptJob() = UpdateDeliveryReceiptsJob().let { job ->
        job.execute(FakeJobContext(job, null))
    }
}
