package org.tekfive.relaykt.email.zeptomail

import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient

/** Minimal ZeptoMail client for the send-mail and email-status APIs. */
open class ZeptoMailClient(
    private val configuration: ZeptoMailConfiguration,
    client: OkHttpClient = RelayHttpClient.clientFor(configuration.normalizedBaseUrl, configuration.tls.certificatePins),
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.normalizedBaseUrl, client, executeOverride) {

    override val providerName: String = "ZeptoMail"

    // Send and status use different credentials; each request supplies its own Authorization header.
    override fun authorizationHeaders(): Map<String, String> = emptyMap()

    open fun sendMail(request: ZeptoMailSendRequest): ZeptoMailSendResponse {
        val httpRequest = postJson(url("v1.1", "email"), request.toJsonString(), mapOf("Authorization" to configuration.sendAuthorizationHeader))
        val response = executeSuccessful(httpRequest, "send")!!
        if (response.body.isBlank()) {
            return ZeptoMailSendResponse(status = "success")
        }
        val json = response.jsonBody()
        return ZeptoMailSendResponse(requestId = extractRequestId(json), status = json.string("status") ?: json.string("message"))
    }

    open fun getEmailStatus(emailReference: String): ZeptoMailEmailStatusResponse? {
        val normalizedReference = emailReference.trim()
        val authorization = configuration.oauthAuthorizationHeader
        if (normalizedReference.isBlank() || authorization == null) {
            return null
        }
        val httpRequest = get(url("v1.1", "email", "email-reference", normalizedReference), mapOf("Authorization" to authorization))
        val response = executeSuccessful(httpRequest, "status lookup", notFoundAsNull = true) ?: return null
        if (response.body.isBlank()) {
            return null
        }
        return parseEmailStatusResponse(response.jsonBody())
    }

    private fun extractRequestId(json: JsonObject): String? {
        (json.string("request_id") ?: json.string("requestId"))?.takeIf { it.isNotBlank() }?.let { return it }
        val firstData = json.array("data")?.toReqObjList()?.firstOrNull() ?: return null
        val additionalInfo = firstData.obj("additional_info")
        return firstData.string("request_id") ?: firstData.string("requestId")
            ?: additionalInfo?.string("request_id") ?: additionalInfo?.string("requestId")
            ?: additionalInfo?.obj("email_info")?.string("request_id") ?: additionalInfo?.obj("email_info")?.string("requestId")
    }

    private fun parseEmailStatusResponse(json: JsonObject): ZeptoMailEmailStatusResponse {
        // The email-log API returns "data" as an object; older payloads wrapped it in an array.
        val data = json.obj("data") ?: json.array("data")?.toReqObjList()?.firstOrNull() ?: JsonObject()
        val emailInfo = data.obj("email_info")
        val deliveryDetails = data.obj("email_delivery_details")
        val emailOpen = data.obj("email_tracking_details")?.obj("email_open")

        return ZeptoMailEmailStatusResponse(
            requestId = emailInfo?.string("request_id") ?: data.string("request_id") ?: json.string("request_id"),
            emailReference = emailInfo?.string("email_reference") ?: emailInfo?.string("message_id") ?: emailInfo?.string("messageId"),
            status = emailInfo?.string("status"),
            openCount = emailOpen?.get("event_count")?.int ?: 0,
            hasDeliveredRecipients = hasNonEmptyField(deliveryDetails, "delivered"),
            hasHardBounceRecipients = hasNonEmptyField(deliveryDetails, "hardbounce", "hard_bounce"),
            hasSoftBounceRecipients = hasNonEmptyField(deliveryDetails, "softbounce", "soft_bounce"),
            hasMailFailureRecipients = hasNonEmptyField(deliveryDetails, "mailfailure", "mail_failure"),
            hasProcessFailedRecipients = hasNonEmptyField(deliveryDetails, "processfailed", "process_failed"),
        )
    }

    private fun hasNonEmptyField(json: JsonObject?, vararg fieldNames: String): Boolean {
        if (json == null) {
            return false
        }
        return fieldNames.any { fieldName ->
            json.array(fieldName)?.let { return@any it.items.isNotEmpty() }
            json.obj(fieldName)?.let { return@any true }
            json.string(fieldName)?.let { return@any it.isNotBlank() }
            json.boolean(fieldName) == true
        }
    }
}
