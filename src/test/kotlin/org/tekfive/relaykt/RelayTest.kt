package org.tekfive.relaykt

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.endpoint.StaticEndpointResolver
import org.tekfive.relaykt.provider.ProviderException
import org.tekfive.relaykt.provider.ProviderRegistry
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.team.TeamMessagePriority
import org.tekfive.relaykt.testing.InMemoryProvider
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayTest {

    private val emailEndpoint = Endpoint("mem-email", InMemoryProvider.email.id, JsonObject())
    private val smsEndpoint = Endpoint("mem-sms", InMemoryProvider.sms.id, JsonObject())

    @BeforeTest
    fun setUp() {
        InMemoryProvider.clearAll()
        Relay.registerEndpointResolver(StaticEndpointResolver(emailEndpoint, smsEndpoint))
    }

    @AfterTest
    fun tearDown() {
        Relay.reset()
        ProviderRegistry.reset()
        InMemoryProvider.clearAll()
    }

    @Test
    fun `send delivers synchronously through the endpoint provider`() {
        val result = Relay.send(TestMessages.email(), emailEndpoint)

        assertEquals("memory-email", result.providerId)
        assertEquals(DeliveryStatus.SENT, result.status)
        assertTrue(result.hasMessageId)
        assertEquals(1, InMemoryProvider.email.messages.size)
    }

    @Test
    fun `send resolves endpoints by id through the registered resolver`() {
        Relay.send(TestMessages.sms(), "mem-sms")
        assertEquals(1, InMemoryProvider.sms.messages.size)
        assertFailsWith<IllegalArgumentException> { Relay.send(TestMessages.sms(), "missing") }
    }

    @Test
    fun `send rejects a message whose channel does not match the endpoint provider`() {
        assertFailsWith<IllegalStateException> { Relay.send(TestMessages.sms(), emailEndpoint) }
    }

    @Test
    fun `sendAsync completes on another thread`() {
        val callerThread = Thread.currentThread()
        var sendThread: Thread? = null
        val provider = object : InMemoryProviderSpy() {
            override fun onSend() { sendThread = Thread.currentThread() }
        }
        ProviderRegistry.register(provider)

        val result = Relay.sendAsync(TestMessages.email(), Endpoint("spy", provider.id, JsonObject())).get(10, TimeUnit.SECONDS)

        assertEquals(DeliveryStatus.SENT, result.status)
        assertTrue(sendThread != null && sendThread !== callerThread)
    }

    @Test
    fun `sendAsync surfaces provider failures as RelayException`() {
        InMemoryProvider.email.failNextSendWith(ProviderException("boom", statusCode = 503))

        val failure = assertFailsWith<ExecutionException> {
            Relay.sendAsync(TestMessages.email(), emailEndpoint).get(10, TimeUnit.SECONDS)
        }
        val cause = assertIs<RelayException>(failure.cause)
        assertTrue(cause.recoverable)
    }

    @Test
    fun `validation enforces provider capabilities`() {
        val limited = object : InMemoryProviderSpy(capabilities = emptySet()) {}
        ProviderRegistry.register(limited)
        val endpoint = Endpoint("limited", limited.id, JsonObject())

        assertEquals(Capability.MULTIPLE_RECIPIENTS, assertFailsWith<UnsupportedCapabilityException> {
            Relay.send(TestMessages.email(cc = listOf(TestMessages.cc)), endpoint)
        }.capability)
        assertEquals(Capability.ATTACHMENTS, assertFailsWith<UnsupportedCapabilityException> {
            Relay.send(TestMessages.email(attachments = listOf(TestMessages.attachment())), endpoint)
        }.capability)
        val limitedTeam = object : InMemoryProviderSpy(channel = Channel.TEAM, capabilities = emptySet()) {}
        ProviderRegistry.register(limitedTeam)
        assertEquals(Capability.PRIORITY, assertFailsWith<UnsupportedCapabilityException> {
            Relay.send(TestMessages.team(priority = TeamMessagePriority.HIGH), Endpoint("limited-team", limitedTeam.id, JsonObject()))
        }.capability)
        assertTrue(limited.messages.isEmpty())
    }

    @Test
    fun `validation invokes the provider configuration check for every delivery mode`() {
        val strict = object : InMemoryProviderSpy() {
            override fun validateConfiguration(configuration: JsonObject) {
                require(configuration.string("token") != null) { "token is required" }
            }
        }
        ProviderRegistry.register(strict)
        val invalid = Endpoint("strict", strict.id, JsonObject())

        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.email(), invalid) }
        assertFalse(failure.recoverable)
        assertIs<IllegalArgumentException>(failure.cause)
        assertFailsWith<RelayException> { Relay.sendAsync(TestMessages.email(), invalid) }
        assertFailsWith<RelayException> { Relay.enqueue(TestMessages.email(), invalid) }
        assertTrue(strict.messages.isEmpty())

        Relay.send(TestMessages.email(), Endpoint("strict", strict.id, org.tekfive.jfk.json { "token" set "t" }))
        assertEquals(1, strict.messages.size)
    }

    @Test
    fun `validation enforces the endpoint attachment size limit`() {
        val endpoint = Endpoint("small", InMemoryProvider.email.id, JsonObject(), maxAttachmentsSizeBytes = 4)
        val failure = assertFailsWith<RelayException> {
            Relay.send(TestMessages.email(attachments = listOf(TestMessages.attachment(5))), endpoint)
        }
        assertFalse(failure.recoverable)
        Relay.send(TestMessages.email(attachments = listOf(TestMessages.attachment(4))), endpoint)
        assertEquals(1, InMemoryProvider.email.messages.size)
    }

    @Test
    fun `classify maps provider failures to retryability`() {
        val provider = InMemoryProvider.email
        assertTrue(Relay.classify(ProviderException("x", 429), provider).recoverable)
        assertTrue(Relay.classify(ProviderException("x", 500), provider).recoverable)
        assertFalse(Relay.classify(ProviderException("x", 400), provider).recoverable)
        assertFalse(Relay.classify(ProviderException("x"), provider).recoverable)
        assertTrue(Relay.classify(IOException("x"), provider).recoverable)
        assertFalse(Relay.classify(IllegalStateException("x"), provider).recoverable)
        assertFalse(Relay.classify(IllegalArgumentException("x"), provider).recoverable)
        assertFalse(Relay.classify(RuntimeException("x"), provider).recoverable)
        val passthrough = RelayException("keep", recoverable = true)
        assertTrue(Relay.classify(passthrough, provider) === passthrough)
    }

    @Test
    fun `status honours provider capability and message ids`() {
        val result = Relay.send(TestMessages.email(), emailEndpoint)
        InMemoryProvider.email.setStatus(result.messageId, DeliveryStatus.DELIVERED)

        assertEquals(DeliveryStatus.DELIVERED, Relay.status(result.messageId, emailEndpoint))
        assertNull(Relay.status("unknown-id", emailEndpoint))
        assertNull(Relay.status("", emailEndpoint))
    }

    /** InMemoryProvider variant with a hook and configurable capabilities. */
    private abstract class InMemoryProviderSpy(
        override val channel: Channel = Channel.EMAIL,
        override val capabilities: Set<Capability> = setOf(Capability.ATTACHMENTS, Capability.MULTIPLE_RECIPIENTS, Capability.PRIORITY, Capability.STATUS_LOOKUP),
    ) : org.tekfive.relaykt.provider.Provider<Message> {
        override val id: String = "spy-${System.nanoTime()}"
        val messages = mutableListOf<Message>()
        open fun onSend() {}
        override fun send(message: Message, configuration: JsonObject): SendResult {
            onSend()
            messages.add(message)
            return SendResult("id", id, DeliveryStatus.SENT)
        }
    }
}
