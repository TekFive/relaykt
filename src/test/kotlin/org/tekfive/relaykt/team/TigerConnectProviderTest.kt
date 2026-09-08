package org.tekfive.relaykt.team

import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.support.StubHttp
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.team.tigerconnect.TigerConnectClient
import org.tekfive.relaykt.team.tigerconnect.TigerConnectConfiguration
import org.tekfive.relaykt.team.tigerconnect.TigerConnectProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TigerConnectProviderTest {

    private val endpoint = Endpoint("tc", TigerConnectProvider.id, TigerConnectConfiguration(
        "key", "secret", organizationId = "org-1", senderUserId = "sender-1",
    ).toJsonObject())

    @AfterTest
    fun tearDown() {
        TigerConnectProvider.clientFactory = { TigerConnectClient(it) }
    }

    private fun stub(stub: StubHttp) {
        TigerConnectProvider.clientFactory = { TigerConnectClient(it, executeOverride = stub.execute) }
    }

    // Fixtures use the documented v2 envelopes, tokens, headers and per-recipient statuses.
    private fun user(token: String = "u1") = StubHttp.json(200, """{"status":"success","reply":{"token":"$token","display_name":"Test user"}}""")
    private fun accepted(id: String = "m-1") = StubHttp.json(201, "", mapOf("tt-x-message-id" to id))
    private fun searchResult(type: String, token: String, name: String, org: String = "org-1", role: Boolean = false) = json {
        "type" set "tigertext:entity:$type"
        "organization_id" set org
        "entity" set json {
            "token" set token
            "display_name" set name
            if (role) "metadata" set json { "feature_service" set "role" }
        }
    }
    private fun searchReply(results: List<org.tekfive.jfk.JsonObject> = emptyList(), continuation: String? = null) = StubHttp.json(200, json {
        "status" set "success"
        "reply" set json {
            "results" set results
            "metadata" set json { if (continuation != null) "continuation" set continuation }
        }
    }.toJsonString())
    private fun status(vararg values: String, recalled: Boolean = false) = StubHttp.json(200, json {
        "status" set "success"
        "reply" set json {
            "client_id" set "m-1"
            "is_recalled" set recalled
            "statuses" set values.map { json { "account_id" set "test-account"; "status" set it } }
        }
    }.toJsonString())

    @Test
    fun `sends documented v2 recipient payload with basic auth and message id header`() {
        val stub = StubHttp.routes("/user_lookup/" to user(), "/message" to accepted())
        stub(stub)

        val result = Relay.send(TeamMessage(
            listOf(MessageAddress("doc+test@example.com")), "Synthetic notification", subject = "Test",
            priority = TeamMessagePriority.URGENT,
        ), endpoint)

        assertEquals("m-1", result.messageId)
        assertEquals("/v2/user_lookup/doc+test@example.com", stub.requests.first().request.url.encodedPath)
        val send = stub.requests.last()
        assertEquals("POST", send.method)
        assertEquals("/v2/message", send.request.url.encodedPath)
        assertEquals("message", send.request.url.queryParameter("response_format"))
        assertEquals("Basic a2V5OnNlY3JldA==", send.header("Authorization"))
        assertEquals("org-1", send.header("TT-X-Organization-Key"))
        assertEquals("u1", send.json.string("recipient"))
        assertEquals("Test\n\nSynthetic notification", send.json.string("body"))
        assertEquals(1L, send.json.long("priority"))
        assertEquals("org-1", send.json.string("sender_organization"))
        assertEquals("org-1", send.json.string("recipient_organization"))
        assertNull(send.json.string("targetType"))
        assertNull(send.json.string("targetId"))
        assertNull(send.json.string("subject"))
    }

    @Test
    fun `explicit users groups and distribution lists bypass directory lookup`() {
        val stub = StubHttp.routes("/message" to accepted())
        stub(stub)
        Relay.send(TestMessages.team(to = listOf(
            MessageAddress("user:u1"), MessageAddress("group:g1"), MessageAddress("distribution_list:d1"),
        )), endpoint)
        assertEquals(listOf("u1", "g1", "d1"), stub.requests.map { it.json.string("recipient") })
        assertTrue(stub.requests.all { it.request.url.encodedPath == "/v2/message" })
    }

    @Test
    fun `name lookup filters types organizations roles and partial matches`() {
        val stub = StubHttp { request, body ->
            when (request.url.encodedPath) {
                "/v2/search" -> when (body.asRequiredJsonObject().reqArray("type")[0].string) {
                    "group" -> searchReply(listOf(
                        searchResult("group", "g0", "On-Call backup"),
                        searchResult("group", "wrong-org", "On-Call", org = "org-2"),
                        searchResult("account", "wrong-type", "On-Call"),
                    ))
                    "account" -> searchReply(listOf(
                        searchResult("account", "not-a-role", "On-Call"),
                        searchResult("account", "r1", "On-Call", role = true),
                    ))
                    else -> searchReply()
                }
                "/v2/role_group" -> StubHttp.json(201, """{"status":"success","reply":{"token":"role-group-1"}}""")
                "/v2/message" -> accepted()
                else -> StubHttp.json(404, "")
            }
        }
        stub(stub)
        Relay.send(TestMessages.team(to = listOf(MessageAddress("on-call"))), endpoint)
        val searches = stub.requests.filter { it.request.url.encodedPath == "/v2/search" }
        assertEquals(3, searches.size)
        assertTrue(searches.all { it.json.reqArray("directory")[0].string == "org-1" })
        val roleGroup = stub.requests.single { it.request.url.encodedPath == "/v2/role_group" }
        assertEquals("sender-1", roleGroup.json.string("created_by"))
        assertEquals("sender-1", roleGroup.json.reqArray("members")[0].string)
        assertEquals("r1", roleGroup.json.reqArray("members")[1].string)
        assertEquals("role-group-1", stub.requests.last().json.string("recipient"))
    }

    @Test
    fun `duplicate exact names on later pages prevent all sends`() {
        val stub = StubHttp { request, body ->
            val payload = body.asRequiredJsonObject()
            assertEquals("/v2/search", request.url.encodedPath)
            if (payload.reqArray("type")[0].string != "group") searchReply()
            else if (payload.string("continuation") == null) searchReply(listOf(searchResult("group", "g1", "Team")), "page-2")
            else searchReply(listOf(searchResult("group", "g2", "TEAM")))
        }
        stub(stub)
        val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("Team"))), endpoint) }
        assertTrue(failure.message!!.contains("ambiguous"))
        assertTrue(stub.requests.any { it.json.string("continuation") == "page-2" })
    }

    @Test
    fun `repeated pagination tokens fail instead of selecting an incomplete result`() {
        val stub = StubHttp { _, _ -> searchReply(listOf(searchResult("group", "g1", "Team")), "same-page") }
        stub(stub)
        assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("Team"))), endpoint) }
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun `missing user does not fall through to another recipient type`() {
        val stub = StubHttp.routes("/user_lookup/" to StubHttp.json(404, ""))
        stub(stub)
        assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("missing@example.com"))), endpoint) }
        assertEquals(1, stub.requests.size)
    }

    @Test
    fun `auth errors fail fast and transient lookup errors remain recoverable`() {
        for (code in listOf(401, 403, 408, 429, 503)) {
            val stub = StubHttp.routes("/user_lookup/" to StubHttp.json(code, """{"error":"private response text"}"""))
            stub(stub)
            val failure = assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("doc@example.com"))), endpoint) }
            assertEquals(code in listOf(408, 429, 503), failure.recoverable)
            assertTrue(failure.message!!.contains("HTTP status $code"))
            assertFalse(failure.message!!.contains("private response text"))
            assertEquals(1, stub.requests.size)
        }
    }

    @Test
    fun `role configuration is checked before any recipient receives a message`() {
        val stub = StubHttp.routes("/message" to accepted())
        stub(stub)
        val withoutSender = Endpoint("tc", TigerConnectProvider.id, TigerConnectConfiguration("key", "secret", organizationId = "org-1").toJsonObject())
        assertFailsWith<RelayException> {
            Relay.send(TestMessages.team(to = listOf(MessageAddress("user:u1"), MessageAddress("role:r1"))), withoutSender)
        }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `blank tokens and missing or failed API message ids are rejected`() {
        for (response in listOf(accepted(" "), StubHttp.json(201, "{}"), StubHttp.json(200, """{"status":"fail","error":{"message":"private"}}"""))) {
            stub(StubHttp.routes("/message" to response))
            assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("user:u1"))), endpoint) }
        }
        val stub = StubHttp.routes("/message" to accepted())
        stub(stub)
        assertFailsWith<RelayException> { Relay.send(TestMessages.team(to = listOf(MessageAddress("group:"))), endpoint) }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `full message response id is accepted when no header is present`() {
        stub(StubHttp.routes("/message" to StubHttp.json(201, """{"status":"success","reply":{"message_id":"m-body"}}""")))
        assertEquals("m-body", Relay.send(TestMessages.team(to = listOf(MessageAddress("user:u1"))), endpoint).messageId)
    }

    @Test
    fun `per-recipient message ids are reported and partial sends are not retried`() {
        var count = 0
        stub(StubHttp { _, _ -> accepted("m-${++count}") })
        val message = TestMessages.team(to = listOf(MessageAddress("user:u1"), MessageAddress("user:u2")))
        val result = Relay.send(message, endpoint)
        assertEquals("multi:m-1,m-2", result.messageId)
        assertEquals(mapOf("user:u1" to "m-1", "user:u2" to "m-2"), result.recipientMessageIds)
        count = 0
        stub(StubHttp { _, _ -> if (++count == 1) accepted() else StubHttp.json(503, "") })
        val failure = assertFailsWith<RelayException> { Relay.send(message, endpoint) }
        assertFalse(failure.recoverable)
        assertTrue(failure.message!!.contains("1 of 2"))
    }

    @Test
    fun `status uses every recipient and maps New Read Confirmed and recalled messages`() {
        for ((response, expected) in listOf(
            status("New") to DeliveryStatus.SENT,
            status("Read", "Delivered") to DeliveryStatus.DELIVERED,
            status("Read", "Confirmed") to DeliveryStatus.READ,
            status("Read", "New") to DeliveryStatus.SENT,
            status("Read", "NA") to DeliveryStatus.UNKNOWN,
            status("Read", recalled = true) to DeliveryStatus.FAILED,
            status() to DeliveryStatus.UNKNOWN,
        )) {
            stub(StubHttp.routes("/message/a/status" to response))
            assertEquals(expected, Relay.status("a", endpoint))
        }
    }

    @Test
    fun `aggregate status does not claim delivery when a lookup fails or is missing`() {
        stub(StubHttp.routes(
            "/message/a/status" to status("Read"),
            "/message/b/status" to status("Delivered"),
            "/message/c/status" to StubHttp.json(500, ""),
        ))
        assertEquals(DeliveryStatus.DELIVERED, Relay.status("multi:a,b", endpoint))
        assertEquals(DeliveryStatus.UNKNOWN, Relay.status("multi:a,c", endpoint))
        assertEquals(DeliveryStatus.UNKNOWN, Relay.status("missing", endpoint))
    }

    @Test
    fun `configuration normalizes root URLs preserves version paths and round trips`() {
        assertEquals("https://api.tigertext.me/v2", TigerConnectConfiguration("k", "s").normalizedBaseUrl)
        assertEquals("https://api.tigertext.me/v2", TigerConnectConfiguration("k", "s", " https://api.tigertext.me/ ").normalizedBaseUrl)
        assertEquals("http://localhost:8080/custom/v2", TigerConnectConfiguration("k", "s", "http://localhost:8080/custom/v2/").normalizedBaseUrl)
        val parsed = TigerConnectConfiguration.fromJson(endpoint.configuration)
        assertEquals("org-1", parsed.organizationId)
        assertEquals("sender-1", parsed.senderUserId)
        assertFalse(parsed.toString().contains("secret"))
        for (url in listOf("http://example.com", "https://example.com?key=secret", "https://key:secret@example.com", "https://example.com/#fragment")) {
            assertFailsWith<IllegalArgumentException> { TigerConnectConfiguration("k", "s", url) }
        }
    }
}
