package org.tekfive.relaykt.team

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.team.tigerconnect.TigerConnectClient
import org.tekfive.relaykt.team.tigerconnect.TigerConnectConfiguration
import org.tekfive.relaykt.team.tigerconnect.TigerConnectProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Only runs through tigerConnectLiveTest. Requires a provisioned test tenant; see docs/tigerconnect.md. */
@Tag("tigerconnect-live")
@Timeout(120)
class TigerConnectLiveTest {

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Set $name for the TigerConnect live tests; see docs/tigerconnect.md")

    private fun configuration() = TigerConnectConfiguration(
        apiKey = requiredEnvironment("TIGERCONNECT_API_KEY"),
        apiSecret = requiredEnvironment("TIGERCONNECT_API_SECRET"),
        // Always explicit: this task must not fall back to the provider's production URL.
        baseUrl = requiredEnvironment("TIGERCONNECT_TEST_BASE_URL"),
        organizationId = requiredEnvironment("TIGERCONNECT_ORGANIZATION_ID"),
        senderUserId = System.getenv("TIGERCONNECT_SENDER_USER_ID")?.takeIf { it.isNotBlank() },
    )

    @Test
    fun `looks up a provisioned test user without sending`() {
        val configuration = configuration()
        val address = requiredEnvironment("TIGERCONNECT_TEST_USER")
        val user = TigerConnectClient(configuration).findUser(address)
        assertNotNull(user, "Test user was not found")
        assertFalse(user.id.isNullOrBlank(), "User lookup did not return a token")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TIGERCONNECT_TEST_MESSAGE_ID", matches = ".+")
    fun `reads an existing test message receipt`() {
        val client = TigerConnectClient(configuration())
        val status = client.getMessageStatus(requiredEnvironment("TIGERCONNECT_TEST_MESSAGE_ID"))
        assertNotNull(status, "Test message was not found or has expired")
        assertTrue(status.isRecalled || status.recipientStatuses.isNotEmpty(), "Status response did not contain recipient receipts")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TIGERCONNECT_SEND_TEST_MESSAGE", matches = "true")
    fun `sends one synthetic notification to the explicitly configured recipient`() {
        val configuration = configuration()
        val recipient = requiredEnvironment("TIGERCONNECT_TEST_RECIPIENT")
        val endpoint = Endpoint("tigerconnect-live-test", TigerConnectProvider.id, configuration.toJsonObject())
        val result = Relay.send(TeamMessage(
            to = listOf(MessageAddress(recipient)),
            body = "RelayKt integration test ${UUID.randomUUID()}. Synthetic test data only.",
        ), endpoint)
        assertTrue(result.messageId.isNotBlank(), "Send response did not return a message id")
        // Direct client lookup lets authentication/HTTP failures fail this check instead of becoming UNKNOWN.
        assertNotNull(TigerConnectClient(configuration).getMessageStatus(result.messageId), "Sent message receipt was not found")
    }
}
