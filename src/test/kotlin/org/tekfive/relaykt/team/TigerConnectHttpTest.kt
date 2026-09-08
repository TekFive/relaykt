package org.tekfive.relaykt.team

import com.sun.net.httpserver.HttpServer
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.team.tigerconnect.TigerConnectConfiguration
import org.tekfive.relaykt.team.tigerconnect.TigerConnectProvider
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class TigerConnectHttpTest {

    @Test
    fun `real HTTP transport performs lookup send and receipt against a local server`() {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val route = "${exchange.requestMethod} ${exchange.requestURI.path}"
            requests.add(route to exchange.requestHeaders.getFirst("Authorization"))
            exchange.requestBody.use { it.readBytes() }
            val response = when (route) {
                "GET /v2/user_lookup/test@example.com" -> """{"status":"success","reply":{"token":"test-user"}}"""
                "POST /v2/message" -> ""
                "GET /v2/message/local-message/status" -> """{"status":"success","reply":{"client_id":"local-message","is_recalled":false,"statuses":[{"account_id":"test-user","status":"Read"}]}}"""
                else -> null
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (route == "POST /v2/message") {
                exchange.responseHeaders.add("TT-X-Message-Id", "local-message")
                exchange.sendResponseHeaders(201, -1)
            } else {
                val bytes = (response ?: "{}").toByteArray()
                exchange.sendResponseHeaders(if (response == null) 404 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val endpoint = Endpoint("tc-local", TigerConnectProvider.id, TigerConnectConfiguration(
                "test-key", "test-secret", baseUrl = "http://127.0.0.1:${server.address.port}",
            ).toJsonObject())
            val result = Relay.send(TeamMessage(listOf(MessageAddress("test@example.com")), "Synthetic local test"), endpoint)
            assertEquals("local-message", result.messageId)
            assertEquals(DeliveryStatus.READ, Relay.status(result.messageId, endpoint))
            assertEquals(listOf(
                "GET /v2/user_lookup/test@example.com",
                "POST /v2/message",
                "GET /v2/message/local-message/status",
            ), requests.map { it.first })
            assertEquals(setOf("Basic dGVzdC1rZXk6dGVzdC1zZWNyZXQ="), requests.map { it.second }.toSet())
        } finally {
            server.stop(0)
        }
    }
}
