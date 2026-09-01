package org.tekfive.relaykt.team.slack

import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient
import org.tekfive.relaykt.provider.ProviderException

data class SlackPostMessageResponse(val channel: String, val ts: String) {
    val messageId: String
        get() = "$channel:$ts"
}

/** Minimal Slack Web API client: post messages and resolve users/channels. */
open class SlackClient(
    private val configuration: SlackConfiguration,
    client: OkHttpClient = RelayHttpClient.clientFor(configuration.normalizedBaseUrl, configuration.tls.certificatePins),
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.normalizedBaseUrl, client, executeOverride) {

    override val providerName: String = "Slack"

    override fun authorizationHeaders(): Map<String, String> = mapOf("Authorization" to configuration.authorizationHeader)

    open fun postMessage(channel: String, text: String): SlackPostMessageResponse {
        val response = call(postJson(url("api", "chat.postMessage"), json { "channel" set channel; "text" set text }.toJsonString()))
        val ts = response.string("ts") ?: response.obj("message")?.string("ts")
            ?: throw ProviderException("Slack chat.postMessage response did not include a message timestamp")
        return SlackPostMessageResponse(response.string("channel") ?: channel, ts)
    }

    open fun lookupUserByEmail(email: String): String? {
        val response = callOrNull(get(url("api", "users.lookupByEmail", query = mapOf("email" to email))), ignoredErrors = setOf("users_not_found"))
        return response?.obj("user")?.string("id")
    }

    open fun openConversation(userId: String): String? {
        val response = call(postJson(url("api", "conversations.open"), json { "users" set userId; "return_im" set true }.toJsonString()))
        return response.obj("channel")?.string("id")
    }

    open fun findConversationByName(name: String): String? {
        // Slack channel names are always lowercase; normalize before comparing.
        val normalizedName = name.trim().removePrefix("#").lowercase()
        if (normalizedName.isBlank()) {
            return null
        }
        var cursor: String? = null
        do {
            val query = mutableMapOf("types" to "public_channel,private_channel", "exclude_archived" to "true", "limit" to "1000")
            cursor?.let { query["cursor"] = it }
            val response = call(get(url("api", "conversations.list", query = query)))
            val match = response.array("channels")?.toReqObjList()?.firstOrNull { channel ->
                channel.string("name")?.lowercase() == normalizedName ||
                    channel.string("name_normalized")?.lowercase() == normalizedName ||
                    channel.string("id")?.equals(normalizedName, ignoreCase = true) == true
            }
            if (match != null) {
                return match.string("id")
            }
            cursor = response.obj("response_metadata")?.string("next_cursor")?.takeIf { it.isNotBlank() }
        } while (cursor != null)
        return null
    }

    private fun call(request: Request): JsonObject {
        return callOrNull(request, ignoredErrors = emptySet())
            ?: throw ProviderException("Slack request failed without response details")
    }

    /** Slack signals API errors inside 200 responses with `ok: false` and a stable `error` token. */
    private fun callOrNull(request: Request, ignoredErrors: Set<String>): JsonObject? {
        val response = executeSuccessful(request, "request")!!
        val body = response.jsonBody()
        if (body.boolean("ok") == true) {
            return body
        }
        val error = body.string("error") ?: "unknown_error"
        if (error in ignoredErrors) {
            return null
        }
        throw ProviderException("Slack request failed: $error")
    }
}
