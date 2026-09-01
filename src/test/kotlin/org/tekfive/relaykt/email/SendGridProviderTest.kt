package org.tekfive.relaykt.email

import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.email.sendgrid.SendGridClient
import org.tekfive.relaykt.email.sendgrid.SendGridConfiguration
import org.tekfive.relaykt.email.sendgrid.SendGridEmailActivityResponse
import org.tekfive.relaykt.email.sendgrid.SendGridEmailEvent
import org.tekfive.relaykt.email.sendgrid.SendGridProvider
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.tls.TlsConfiguration
import org.tekfive.relaykt.tls.TlsCertificatePinsTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendGridProviderTest {

    private val configuration = json { "apiKey" set "SG.key" }
    private val endpoint = Endpoint("sg", SendGridProvider.id, configuration)

    @AfterTest
    fun tearDown() {
        SendGridProvider.clientFactory = { SendGridClient(it) }
    }

    private fun stub(stub: StubHttp) {
        SendGridProvider.clientFactory = { SendGridClient(it, executeOverride = stub.execute) }
    }

    @Test
    fun `send posts the v3 mail request and reads the message id header`() {
        val stub = StubHttp.routes("/v3/mail/send" to StubHttp.json(202, "", mapOf("X-Message-Id" to "msg-123")))
        stub(stub)

        val result = Relay.send(TestMessages.email(cc = listOf(TestMessages.cc), attachments = listOf(TestMessages.attachment()), html = true), endpoint)

        assertEquals("msg-123", result.messageId)
        assertEquals(DeliveryStatus.QUEUED, result.status)
        val request = stub.requests.single()
        assertEquals("Bearer SG.key", request.header("Authorization"))
        val payload = request.json
        assertEquals("noreply@example.com", payload.obj("from")?.string("email"))
        assertEquals("text/html", payload.array("content")?.toReqObjList()?.single()?.string("type"))
        assertEquals(1, payload.array("personalizations")?.toReqObjList()?.single()?.array("cc")?.items?.size)
        assertEquals("file.txt", payload.array("attachments")?.toReqObjList()?.single()?.string("filename"))
    }

    @Test
    fun `send failures carry only the status code and classify for retry`() {
        stub(StubHttp.routes("/v3/mail/send" to StubHttp.json(429, """{"errors":[{"message":"to@example.com is bad"}]}""")))

        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.email(), endpoint) }

        assertTrue(failure.recoverable)
        assertTrue(failure.message!!.contains("429"))
        assertTrue(!failure.message!!.contains("example.com"))
    }

    @Test
    fun `status maps activity events, preferring the most recent`() {
        stub(StubHttp.routes("/v3/messages" to StubHttp.json(200, """{"messages":[{"msg_id":"msg-123.abc","status":"processed","events":[{"event_name":"processed"},{"event_name":"delivered"},{"event_name":"open"}]}]}""")))
        assertEquals(DeliveryStatus.OPENED, Relay.status("msg-123", endpoint))

        stub(StubHttp.routes("/v3/messages" to StubHttp.json(403, "")))
        assertNull(Relay.status("msg-123", endpoint))

        assertEquals(DeliveryStatus.FAILED, SendGridProvider.mapActivity(SendGridEmailActivityResponse(events = listOf(SendGridEmailEvent("bounce", null, null)))))
        assertEquals(DeliveryStatus.UNKNOWN, SendGridProvider.mapActivity(SendGridEmailActivityResponse(status = "weird")))
    }

    @Test
    fun `configuration requires an api key and redacts it`() {
        assertFailsWith<IllegalArgumentException> { SendGridProvider.validateConfiguration(json { "apiKey" set "" }) }
        assertTrue(!SendGridConfiguration("SG.secret").toString().contains("secret"))
        assertFailsWith<IllegalArgumentException> { SendGridConfiguration("SG.key", baseUrl = "http://api.sendgrid.com") }
        SendGridConfiguration("SG.key", baseUrl = "http://localhost:9999/")
        val pinned = SendGridConfiguration.fromJson(json {
            "apiKey" set "SG.key"
            "tls" set TlsConfiguration.pinned(TlsCertificatePinsTest.TEST_PIN)
        })
        assertEquals(TlsConfiguration.pinned(TlsCertificatePinsTest.TEST_PIN), pinned.tls)
        assertFailsWith<IllegalArgumentException> {
            SendGridConfiguration("SG.key", tls = TlsConfiguration.pinned("sha256/invalid"))
        }
        assertFailsWith<IllegalArgumentException> {
            SendGridConfiguration(
                "SG.key",
                baseUrl = "http://localhost:9999",
                tls = TlsConfiguration.pinned(TlsCertificatePinsTest.TEST_PIN),
            )
        }
    }
}
