package org.tekfive.relaykt.email.sendgrid

import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient

/** Minimal Twilio SendGrid client covering mail send and email-activity lookup. */
open class SendGridClient(
    private val configuration: SendGridConfiguration,
    client: OkHttpClient = RelayHttpClient.clientFor(configuration.normalizedBaseUrl, configuration.tls.certificatePins),
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.normalizedBaseUrl, client, executeOverride) {

    override val providerName: String = "SendGrid"

    override fun authorizationHeaders(): Map<String, String> = mapOf("Authorization" to configuration.authorizationHeader)

    open fun sendMail(request: SendGridMailSendRequest): SendGridMailSendResponse {
        val response = executeSuccessful(postJson(url("v3", "mail", "send"), request.toJsonString()), "mail send")!!
        val status = when (response.code) {
            202 -> "queued"
            200, 201 -> "sent"
            else -> null
        }
        return SendGridMailSendResponse(messageId = extractMessageId(response), status = status)
    }

    open fun getEmailActivity(messageId: String): SendGridEmailActivityResponse? {
        val normalizedMessageId = messageId.trim()
        if (normalizedMessageId.isBlank()) {
            return null
        }
        val request = get(url("v3", "messages", query = mapOf("query" to buildActivityQuery(normalizedMessageId))))
        val response = execute(request)
        if (response.code == 404 || response.code == 403) {
            // 403: the API key lacks the Email Activity permission; treat as "no status available".
            return null
        }
        if (!response.isSuccessful) {
            throw org.tekfive.relaykt.provider.ProviderException("SendGrid activity lookup failed with HTTP status ${response.code}", statusCode = response.code)
        }
        return parseActivityResponse(response.jsonBody(), normalizedMessageId)
    }

    private fun buildActivityQuery(messageId: String): String {
        val escaped = messageId
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "msg_id LIKE '$escaped%'"
    }

    private fun extractMessageId(response: HttpResponse): String? {
        response.header("X-Message-Id")?.takeIf { it.isNotBlank() }?.let { return it }
        if (response.body.isBlank()) {
            return null
        }
        val json = response.jsonBody()
        return json.string("message_id") ?: json.string("messageId") ?: json.string("id")
    }

    private fun parseActivityResponse(json: JsonObject, requestedMessageId: String): SendGridEmailActivityResponse {
        val record = selectActivityRecord(json, requestedMessageId)
        val events = record.array("events")?.toReqObjList()?.mapNotNull { event ->
            val eventName = event.string("event_name") ?: event.string("event") ?: event.string("status")
            if (eventName.isNullOrBlank()) null else SendGridEmailEvent(eventName, event.string("status"), event.string("timestamp"))
        }.orEmpty()
        return SendGridEmailActivityResponse(
            messageId = activityMessageId(record),
            status = record.string("status"),
            events = events,
        )
    }

    /** Picks the activity record matching the requested id (exact, then longest prefix match). */
    private fun selectActivityRecord(json: JsonObject, requestedMessageId: String): JsonObject {
        val records = json.array("messages")?.toReqObjList().orEmpty()
        if (records.isEmpty()) {
            return json
        }
        val candidates = records.mapNotNull { record -> activityMessageId(record)?.let { it to record } }
        if (candidates.isEmpty()) {
            return records.first()
        }
        candidates.firstOrNull { (messageId, _) -> messageId == requestedMessageId }?.let { return it.second }
        val prefixMatches = candidates.filter { (messageId, _) -> messageId.startsWith(requestedMessageId) }
        val pool = prefixMatches.ifEmpty { candidates }
        return pool.maxWith(compareBy<Pair<String, JsonObject>> { it.first.length }.thenBy { it.first }).second
    }

    private fun activityMessageId(record: JsonObject): String? {
        return record.string("msg_id") ?: record.string("msgId") ?: record.string("message_id")
            ?: record.string("messageId") ?: record.string("id")
    }
}
