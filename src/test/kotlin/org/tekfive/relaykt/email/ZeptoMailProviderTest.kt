package org.tekfive.relaykt.email

import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.email.zeptomail.ZeptoMailClient
import org.tekfive.relaykt.email.zeptomail.ZeptoMailEmailStatusResponse
import org.tekfive.relaykt.email.zeptomail.ZeptoMailProvider
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZeptoMailProviderTest {

    @AfterTest
    fun tearDown() {
        ZeptoMailProvider.clientFactory = { ZeptoMailClient(it) }
    }

    private fun stub(stub: StubHttp) {
        ZeptoMailProvider.clientFactory = { ZeptoMailClient(it, executeOverride = stub.execute) }
    }

    @Test
    fun `send uses the send token and maps html and text bodies`() {
        val stub = StubHttp.routes("/v1.1/email" to StubHttp.json(201, """{"data":[{"additional_info":[],"message":"OK"}],"message":"OK","request_id":"req-1"}"""))
        stub(stub)
        val endpoint = Endpoint("zepto", ZeptoMailProvider.id, json { "sendMailToken" set "tok"; "trackOpens" set true })

        val result = Relay.send(TestMessages.email(html = true), endpoint)

        assertEquals("req-1", result.messageId)
        assertEquals(DeliveryStatus.QUEUED, result.status)
        val request = stub.requests.single()
        assertEquals("Zoho-enczapikey tok", request.header("Authorization"))
        assertEquals("<p>Hello & goodbye</p>", request.json.string("htmlbody"))
        assertNull(request.json.string("textbody"))
        assertEquals(true, request.json.boolean("track_opens"))
        assertEquals("to@example.com", request.json.array("to")?.toReqObjList()?.single()?.obj("email_address")?.string("address"))
    }

    @Test
    fun `status requires an oauth token and maps delivery details`() {
        val noOauth = Endpoint("zepto", ZeptoMailProvider.id, json { "sendMailToken" set "tok" })
        assertNull(Relay.status("req-1", noOauth))

        val stub = StubHttp.routes("/email-reference/req-1" to StubHttp.json(200, """{"data":{"email_info":{"email_reference":"req-1","message_id":"<x@y>","status":"delivered","request_id":"req-1"},"email_delivery_details":{"delivered":[{"email_address":"to@example.com"}]},"email_tracking_details":{"email_open":{"event_count":0}}},"status":"success"}"""))
        stub(stub)
        val withOauth = Endpoint("zepto", ZeptoMailProvider.id, json { "sendMailToken" set "tok"; "oauthAccessToken" set "oauth" })
        assertEquals(DeliveryStatus.DELIVERED, Relay.status("req-1", withOauth))
        assertEquals("Zoho-oauthtoken oauth", stub.requests.single().header("Authorization"))

        assertEquals(DeliveryStatus.OPENED, ZeptoMailProvider.mapStatus(ZeptoMailEmailStatusResponse(openCount = 2, hasHardBounceRecipients = true)))
        assertEquals(DeliveryStatus.FAILED, ZeptoMailProvider.mapStatus(ZeptoMailEmailStatusResponse(hasSoftBounceRecipients = true)))
        assertEquals(DeliveryStatus.UNKNOWN, ZeptoMailProvider.mapStatus(ZeptoMailEmailStatusResponse(hasSoftBounceRecipients = true, hasDeliveredRecipients = true)))
        assertEquals(DeliveryStatus.SENT, ZeptoMailProvider.mapStatus(ZeptoMailEmailStatusResponse(status = "processed")))
    }
}
