package org.tekfive.relaykt.sms

import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.UnsupportedCapabilityException
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.sms.twilio.TwilioSmsClient
import org.tekfive.relaykt.sms.twilio.TwilioSmsConfiguration
import org.tekfive.relaykt.sms.twilio.TwilioSmsProvider
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwilioSmsProviderTest {

    private val baseConfiguration = json { "accountSid" set "AC123"; "authToken" set "secret" }

    @AfterTest
    fun tearDown() {
        TwilioSmsProvider.clientFactory = { TwilioSmsClient(it) }
    }

    private fun stub(stub: StubHttp) {
        TwilioSmsProvider.clientFactory = { TwilioSmsClient(it, executeOverride = stub.execute) }
    }

    @Test
    fun `send posts a form to the messages resource using the message from number`() {
        val stub = StubHttp.routes("/Messages.json" to StubHttp.json(201, """{"sid":"SM1","status":"queued"}"""))
        stub(stub)

        val result = Relay.send(TestMessages.sms(), Endpoint("tw", TwilioSmsProvider.id, baseConfiguration))

        assertEquals("SM1", result.messageId)
        assertEquals(DeliveryStatus.QUEUED, result.status)
        val request = stub.requests.single()
        assertTrue(request.url.endsWith("/2010-04-01/Accounts/AC123/Messages.json"))
        assertTrue(request.header("Authorization")!!.startsWith("Basic "))
        assertEquals("To=%2B15555550100&Body=Ping&From=%2B15555550999", request.body)
    }

    @Test
    fun `send falls back to a messaging service when the message has no from`() {
        val stub = StubHttp.routes("/Messages.json" to StubHttp.json(201, """{"sid":"SM2","status":"accepted"}"""))
        stub(stub)
        val endpoint = Endpoint("tw", TwilioSmsProvider.id, baseConfiguration + mapOf("messagingServiceSid" to "MG1"))

        Relay.send(TestMessages.sms(from = null), endpoint)

        assertEquals("To=%2B15555550100&Body=Ping&MessagingServiceSid=MG1", stub.requests.single().body)
    }

    @Test
    fun `send without any sender is a non-recoverable configuration error`() {
        stub(StubHttp.routes())
        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.sms(from = null), Endpoint("tw", TwilioSmsProvider.id, baseConfiguration)) }
        assertFalse(failure.recoverable)
    }

    @Test
    fun `multiple recipients are rejected before any request`() {
        val stub = StubHttp.routes()
        stub(stub)
        assertFailsWith<UnsupportedCapabilityException> {
            Relay.send(TestMessages.sms(to = listOf(MessageAddress("+1555"), MessageAddress("+1666"))), Endpoint("tw", TwilioSmsProvider.id, baseConfiguration))
        }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `status lookups map twilio statuses and treat 404 as unknown message`() {
        stub(StubHttp.routes("/Messages/SM1.json" to StubHttp.json(200, """{"sid":"SM1","status":"delivered"}""")))
        assertEquals(DeliveryStatus.DELIVERED, Relay.status("SM1", Endpoint("tw", TwilioSmsProvider.id, baseConfiguration)))

        stub(StubHttp.routes("/Messages/SM9.json" to StubHttp.json(404, "{}")))
        assertNull(Relay.status("SM9", Endpoint("tw", TwilioSmsProvider.id, baseConfiguration)))

        assertEquals(DeliveryStatus.SENT, TwilioSmsProvider.mapStatus("partially_delivered"))
        assertEquals(DeliveryStatus.READ, TwilioSmsProvider.mapStatus("read"))
        assertEquals(DeliveryStatus.FAILED, TwilioSmsProvider.mapStatus("undelivered"))
        assertEquals(DeliveryStatus.UNKNOWN, TwilioSmsProvider.mapStatus("received"))
    }

    @Test
    fun `configuration enforces https and a single sender strategy`() {
        assertFailsWith<IllegalArgumentException> { TwilioSmsConfiguration("AC", "tok", baseUrl = "http://api.twilio.com") }
        TwilioSmsConfiguration("AC", "tok", baseUrl = "http://localhost:8080")
        assertFailsWith<IllegalArgumentException> { TwilioSmsConfiguration("AC", "tok", fromNumber = "+1", messagingServiceSid = "MG") }
        assertFalse(TwilioSmsConfiguration("AC", "tok").toString().contains("tok"))
    }
}
