package org.tekfive.relaykt.team

import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.team.msteams.MicrosoftTeamsClient
import org.tekfive.relaykt.team.msteams.MicrosoftTeamsConfiguration
import org.tekfive.relaykt.team.msteams.MicrosoftTeamsProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MicrosoftTeamsProviderTest {

    private val endpoint = Endpoint("teams", MicrosoftTeamsProvider.id, json { "webhookUrl" set "https://prod.westus.logic.azure.com/workflows/abc/triggers/manual/paths/invoke?sig=secret" })

    @AfterTest
    fun tearDown() {
        MicrosoftTeamsProvider.clientFactory = { MicrosoftTeamsClient(it) }
    }

    private fun stub(stub: StubHttp) {
        MicrosoftTeamsProvider.clientFactory = { MicrosoftTeamsClient(it, executeOverride = stub.execute) }
    }

    @Test
    fun `posts an adaptive card envelope to the webhook`() {
        val stub = StubHttp { _, _ -> StubHttp.json(202, "") }
        stub(stub)
        val message = TestMessages.team(to = listOf(MessageAddress("ops", "Ops Team"), MessageAddress("lead@example.com")), priority = TeamMessagePriority.HIGH)

        val result = Relay.send(message, endpoint)

        assertEquals(DeliveryStatus.SENT, result.status)
        assertTrue(result.messageId.isNotBlank())
        val request = stub.requests.single()
        assertTrue(request.url.startsWith("https://prod.westus.logic.azure.com/workflows/abc/"))
        assertTrue(request.url.contains("sig=secret"))
        val envelope = request.json
        assertEquals("message", envelope.string("type"))
        val card = envelope.array("attachments")!!.toReqObjList().single().obj("content")!!
        assertEquals("AdaptiveCard", card.string("type"))
        val texts = card.array("body")!!.toReqObjList().map { it.string("text") }
        assertEquals(listOf("Team subject", "Priority: High", "Team body", "Attention: Ops Team, lead@example.com"), texts)
    }

    @Test
    fun `webhook rejections and http failures are reported`() {
        stub(StubHttp { _, _ -> StubHttp.json(200, "Webhook message delivery failed with error: Microsoft Teams endpoint returned HTTP error 400") })
        assertFalse(assertFailsWith<RelayException> { Relay.send(TestMessages.team(), endpoint) }.recoverable)

        stub(StubHttp { _, _ -> StubHttp.json(503, "") })
        assertTrue(assertFailsWith<RelayException> { Relay.send(TestMessages.team(), endpoint) }.recoverable)
    }

    @Test
    fun `configuration requires an https url and has no status lookup`() {
        assertFailsWith<IllegalArgumentException> { MicrosoftTeamsConfiguration("http://example.com/hook") }
        assertFalse(MicrosoftTeamsConfiguration("https://example.com/hook?sig=abc").toString().contains("abc"))
        assertNull(Relay.status("x", endpoint))
    }
}
