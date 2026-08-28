package org.tekfive.relaykt.team

import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.team.slack.SlackClient
import org.tekfive.relaykt.team.slack.SlackProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlackProviderTest {

    private val endpoint = Endpoint("slack", SlackProvider.id, json { "botToken" set "xoxb-1" })

    @AfterTest
    fun tearDown() {
        SlackProvider.clientFactory = { SlackClient(it) }
    }

    private fun stub(stub: StubHttp) {
        SlackProvider.clientFactory = { SlackClient(it, executeOverride = stub.execute) }
    }

    @Test
    fun `resolves channel names, emails, and explicit ids before posting`() {
        val stub = StubHttp.routes(
            "/api/conversations.list" to StubHttp.json(200, """{"ok":true,"channels":[{"id":"C0GENERAL1","name":"general"}],"response_metadata":{"next_cursor":""}}"""),
            "/api/users.lookupByEmail" to StubHttp.json(200, """{"ok":true,"user":{"id":"U0USER0001"}}"""),
            "/api/conversations.open" to StubHttp.json(200, """{"ok":true,"channel":{"id":"D0DM000001"}}"""),
            "/api/chat.postMessage" to StubHttp.json(200, """{"ok":true,"channel":"X","ts":"1.2"}"""),
        )
        stub(stub)
        val message = TestMessages.team(to = listOf(MessageAddress("#general"), MessageAddress("person@example.com"), MessageAddress("<@U0USER0002>")))

        val result = Relay.send(message, endpoint)

        assertEquals(DeliveryStatus.SENT, result.status)
        assertTrue(result.messageId.startsWith("multi:"))
        val posts = stub.requests.filter { it.url.contains("chat.postMessage") }
        assertEquals(3, posts.size)
        assertEquals("*Team subject*\n\nTeam body", posts.first().json.string("text"))
        assertEquals("Bearer xoxb-1", posts.first().header("Authorization"))
    }

    @Test
    fun `an unresolved recipient aborts before anything is posted`() {
        val stub = StubHttp.routes(
            "/api/users.lookupByEmail" to StubHttp.json(200, """{"ok":false,"error":"users_not_found"}"""),
            "/api/chat.postMessage" to StubHttp.json(200, """{"ok":true,"ts":"1"}"""),
        )
        stub(stub)

        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("nobody@example.com"))), endpoint) }

        assertFalse(failure.recoverable)
        assertTrue(stub.requests.none { it.url.contains("chat.postMessage") })
    }

    @Test
    fun `slack api errors are scrubbed and rate limits are recoverable`() {
        stub(StubHttp.routes("/api/chat.postMessage" to StubHttp.json(200, """{"ok":false,"error":"channel_not_found"}""")))
        val apiFailure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("C0MISSING01"))), endpoint) }
        assertFalse(apiFailure.recoverable)
        assertTrue(apiFailure.message!!.contains("channel_not_found"))

        stub(StubHttp.routes("/api/chat.postMessage" to StubHttp.json(429, "")))
        assertTrue(assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("C0MISSING01"))), endpoint) }.recoverable)
    }

    @Test
    fun `transient lookup failures are recoverable instead of unresolved`() {
        val stub = StubHttp.routes(
            "/api/users.lookupByEmail" to StubHttp.json(429, ""),
            "/api/chat.postMessage" to StubHttp.json(200, """{"ok":true,"ts":"1"}"""),
        )
        stub(stub)

        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("person@example.com"))), endpoint) }

        assertTrue(failure.recoverable)
        assertTrue(stub.requests.none { it.url.contains("chat.postMessage") })
    }

    @Test
    fun `configuration requires https`() {
        assertFailsWith<IllegalArgumentException> { org.tekfive.relaykt.team.slack.SlackConfiguration("xoxb", baseUrl = "http://slack.com") }
        assertFailsWith<IllegalArgumentException> { org.tekfive.relaykt.team.tigerconnect.TigerConnectConfiguration("k", "s", baseUrl = "http://api.tigertext.me") }
        assertFailsWith<IllegalArgumentException> { org.tekfive.relaykt.email.zeptomail.ZeptoMailConfiguration("t", baseUrl = "http://api.zeptomail.com") }
    }

    @Test
    fun `slack has no status lookup`() {
        assertNull(Relay.status("C:1", endpoint))
    }
}
