package org.tekfive.relaykt.team

import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.team.tigerconnect.TigerConnectClient
import org.tekfive.relaykt.team.tigerconnect.TigerConnectProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TigerConnectProviderTest {

    private val endpoint = Endpoint("tc", TigerConnectProvider.id, json { "apiKey" set "key"; "apiSecret" set "secret" })

    @AfterTest
    fun tearDown() {
        TigerConnectProvider.clientFactory = { TigerConnectClient(it) }
    }

    private fun stub(stub: StubHttp) {
        TigerConnectProvider.clientFactory = { TigerConnectClient(it, executeOverride = stub.execute) }
    }

    @Test
    fun `resolves users by email and roles by exact name then sends with priority`() {
        val stub = StubHttp.routes(
            "/users?email=" to StubHttp.json(200, """{"users":[{"id":"u1","email":"doc@example.com"}]}"""),
            "/groups?name=" to StubHttp.json(404, ""),
            "/roles?name=on-call" to StubHttp.json(200, """{"roles":[{"id":"r1","name":"On-Call"},{"id":"r2","name":"on-call-backup"}]}"""),
            "/message" to StubHttp.json(200, """{"messageId":"m-1","status":"sent"}"""),
        )
        stub(stub)
        val message = TestMessages.team(to = listOf(MessageAddress("doc@example.com"), MessageAddress("on-call")), priority = TeamMessagePriority.URGENT)

        val result = Relay.send(message, endpoint)

        assertEquals("multi:m-1,m-1", result.messageId)
        val sends = stub.requests.filter { it.method == "POST" }
        assertEquals(2, sends.size)
        assertEquals("user", sends[0].json.string("targetType"))
        assertEquals("u1", sends[0].json.string("targetId"))
        assertEquals("role", sends[1].json.string("targetType"))
        assertEquals("r1", sends[1].json.string("targetId"))
        assertEquals("urgent", sends[1].json.string("priority"))
    }

    @Test
    fun `ambiguous name matches leave the recipient unresolved`() {
        stub(StubHttp.routes(
            "/groups?name=" to StubHttp.json(200, """{"groups":[{"id":"g1","name":"Team"},{"id":"g2","name":"team"}]}"""),
            "/roles?name=" to StubHttp.json(200, "{}"),
            "/distribution-lists?name=" to StubHttp.json(200, "{}"),
        ))
        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("team"))), endpoint) }
        assertTrue(failure.message!!.contains("1 of 1"))
    }

    @Test
    fun `transient lookup failures are recoverable instead of unresolved`() {
        stub(StubHttp.routes("/users?email=" to StubHttp.json(503, "")))
        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("doc@example.com"))), endpoint) }
        assertTrue(failure.recoverable)
    }

    @Test
    fun `per-recipient message ids are reported`() {
        var counter = 0
        val stub = StubHttp { request, _ ->
            when {
                request.url.encodedPath.startsWith("/users") -> StubHttp.json(200, """{"users":[{"id":"u-${request.url.queryParameter("email")}"}]}""")
                request.url.encodedPath == "/message" -> StubHttp.json(200, """{"messageId":"m-${++counter}"}""")
                else -> StubHttp.json(404, "")
            }
        }
        stub(stub)

        val result = Relay.send(TestMessages.team(to = listOf(MessageAddress("a@example.com"), MessageAddress("b@example.com"))), endpoint)

        assertEquals("multi:m-1,m-2", result.messageId)
        assertEquals(mapOf("a@example.com" to "m-1", "b@example.com" to "m-2"), result.recipientMessageIds)
        assertEquals("m-2", result.messageIdFor("b@example.com"))
        assertEquals("multi:m-1,m-2", result.messageIdFor("other@example.com"))
    }

    @Test
    fun `status aggregates per-recipient results conservatively`() {
        stub(StubHttp.routes(
            "/message/a/status" to StubHttp.json(200, """{"status":"read"}"""),
            "/message/b/status" to StubHttp.json(200, """{"status":"delivered"}"""),
            "/message/c/status" to StubHttp.json(500, ""),
        ))
        assertEquals(DeliveryStatus.DELIVERED, Relay.status("multi:a,b", endpoint))
        assertEquals(DeliveryStatus.READ, Relay.status("a", endpoint))
        assertEquals(DeliveryStatus.SENT, Relay.status("multi:a,c", endpoint))

        assertEquals(DeliveryStatus.FAILED, TigerConnectProvider.aggregateStatus(listOf(DeliveryStatus.READ, DeliveryStatus.FAILED)))
        assertEquals(DeliveryStatus.UNKNOWN, TigerConnectProvider.aggregateStatus(listOf(DeliveryStatus.UNKNOWN)))
    }
}
