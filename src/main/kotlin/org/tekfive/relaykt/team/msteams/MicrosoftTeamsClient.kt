package org.tekfive.relaykt.team.msteams

import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient
import org.tekfive.relaykt.provider.ProviderException

/** Posts Adaptive Card envelopes to a Microsoft Teams webhook. */
open class MicrosoftTeamsClient(
    private val configuration: MicrosoftTeamsConfiguration,
    client: OkHttpClient = RelayHttpClient.client,
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.webhookUrl, client, executeOverride) {

    override val providerName: String = "Microsoft Teams"

    override fun authorizationHeaders(): Map<String, String> = emptyMap()

    /** Returns the webhook's response body (Power Automate returns a JSON activity id; legacy webhooks return "1"). */
    open fun post(envelope: JsonObject): String {
        val response = executeSuccessful(postJson(url(), envelope.toJsonString()), "webhook post")!!
        // Legacy Office 365 connectors report card rejection with a 200 and a textual error body.
        val body = response.body.trim()
        if (body.startsWith("Webhook message delivery failed", ignoreCase = true) || body.contains("Error", ignoreCase = true) && body.length < 200 && !body.startsWith("{")) {
            throw ProviderException("Microsoft Teams webhook rejected the message")
        }
        return body
    }
}
