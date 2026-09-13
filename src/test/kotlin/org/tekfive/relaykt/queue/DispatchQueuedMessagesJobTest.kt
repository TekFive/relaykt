package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.db.db
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobCoordinator
import org.tekfive.keep.job.JobConfiguration
import org.tekfive.keep.job.JobRegistry
import org.tekfive.keep.job.ack.AckJobConfiguration
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.endpoint.StaticEndpointResolver
import org.tekfive.relaykt.support.FakeJobContext
import org.tekfive.relaykt.support.TestDatabase
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.testing.InMemoryProvider
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispatchQueuedMessagesJobTest {
    private val endpoint = Endpoint("mem-email", InMemoryProvider.email.id, JsonObject())

    @BeforeAll
    fun startDatabase() {
        assumeTrue(TestDatabase.dockerAvailable, "Docker is required for queue integration tests")
        TestDatabase.ensureStarted()
    }

    @BeforeTest
    fun setUp() {
        TestDatabase.truncateAll()
        InMemoryProvider.clearAll()
        Relay.registerEndpointResolver(StaticEndpointResolver(endpoint))
    }

    @AfterTest
    fun tearDown() {
        Relay.reset()
        InMemoryProvider.clearAll()
    }

    @Test
    fun `Keep schedules dispatch and sends without a Relay worker thread`() {
        val id = Relay.enqueue(TestMessages.email(), endpoint)
        val registry = JobRegistry().apply {
            register(DispatchQueuedMessagesJob)
            register(SendQueuedMessageJob)
        }
        val configuration = object : JobConfiguration by AckJobConfiguration {
            override val pollSeconds = 1
        }
        val coordinator = JobCoordinator("relay-dispatch-test", registry, configuration)
        try {
            coordinator.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(SEND_TIMEOUT_SECONDS)
            while (MessageQueue.find(id)?.state != QueuedMessageState.SENT && System.nanoTime() < deadline) {
                Thread.sleep(STATE_CHECK_MILLIS)
            }
            assertEquals(QueuedMessageState.SENT, MessageQueue.find(id)?.state)
            assertEquals(1, InMemoryProvider.email.messages.size)
            assertTrue(db { JobRecordsTable.selectAll().where { JobRecordsTable.type eq DispatchQueuedMessagesJob.jobTypeIdentifier }.count() > 0 })
        } finally {
            coordinator.stop(waitForStop = true)
        }
    }

    @Test
    fun `one scheduled sweep drains several batches without duplicate jobs`() {
        val ids = List(MessageQueueProcessor.batchSizeAck() * 2 + 1) { Relay.enqueue(TestMessages.email(), endpoint) }
        runDispatch()
        assertTrue(ids.all { MessageQueue.find(it)?.state == QueuedMessageState.PENDING })
        assertEquals(ids.size.toLong(), sendJobs())

        runDispatch()
        assertEquals(ids.size.toLong(), sendJobs())
    }

    @Test
    fun `sweep recovers stalled messages and respects deferred and retry times`() {
        val stalled = List(MessageQueueProcessor.batchSizeAck() + 1) { Relay.enqueue(TestMessages.email(), endpoint) }
        val processing = Relay.enqueue(TestMessages.email(), endpoint)
        runDispatch()
        val staleAt = System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(1)
        db {
            QueuedMessageTable.update { it[lastStateChangeAt] = staleAt }
            QueuedMessageTable.update({ QueuedMessageTable.id eq processing }) { it[state] = QueuedMessageState.PROCESSING }
        }
        val later = System.currentTimeMillis() + java.util.concurrent.TimeUnit.HOURS.toMillis(1)
        val deferred = Relay.enqueue(TestMessages.email(), endpoint, QueueOptions(deliverAfter = later))
        val retry = Relay.enqueue(TestMessages.email(), endpoint)
        db {
            QueuedMessageTable.update({ QueuedMessageTable.id eq retry }) {
                it[state] = QueuedMessageState.WAITING_TO_RETRY
                it[nextAttemptAt] = later
            }
        }

        runDispatch()

        assertTrue(stalled.all { MessageQueue.find(it)?.state == QueuedMessageState.PENDING })
        assertEquals(QueuedMessageState.TIMED_OUT, MessageQueue.find(processing)?.state)
        assertEquals(QueuedMessageState.QUEUED, MessageQueue.find(deferred)?.state)
        assertEquals(QueuedMessageState.WAITING_TO_RETRY, MessageQueue.find(retry)?.state)
    }

    @Test
    fun `cancellation preserves completed claims and leaves remaining messages queued`() {
        val first = Relay.enqueue(TestMessages.email(), endpoint)
        val second = Relay.enqueue(TestMessages.email(), endpoint)
        val job = DispatchQueuedMessagesJob()
        val context = object : JobContext by FakeJobContext(job, null) {
            override fun checkIn(now: Long) {
                // Read the committed state directly; the enclosing transaction caches Data objects.
                val state = db { QueuedMessageTable.select(QueuedMessageTable.state).where { QueuedMessageTable.id eq first }.single()[QueuedMessageTable.state] }
                if (state == QueuedMessageState.PENDING) {
                    throw CancellationException("Stop after first claim")
                }
            }
        }

        assertFailsWith<CancellationException> { job.execute(context) }
        assertEquals(QueuedMessageState.PENDING, MessageQueue.find(first)?.state)
        assertEquals(QueuedMessageState.QUEUED, MessageQueue.find(second)?.state)
        assertEquals(1L, sendJobs())

        runDispatch()
        assertEquals(QueuedMessageState.PENDING, MessageQueue.find(second)?.state)
        assertEquals(2L, sendJobs())
    }

    private fun runDispatch() {
        val job = DispatchQueuedMessagesJob()
        assertIs<JobCompleted>(job.execute(FakeJobContext(job, null)))
    }

    private fun sendJobs(): Long = db {
        JobRecordsTable.selectAll().where { JobRecordsTable.type eq SendQueuedMessageJob.jobTypeIdentifier }.count()
    }

    private companion object {
        const val SEND_TIMEOUT_SECONDS = 15L
        const val STATE_CHECK_MILLIS = 25L
    }
}
