package org.tekfive.relaykt.sms.twilio

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient
import org.tekfive.relaykt.provider.ProviderException

data class TwilioSmsSendRequest(
    val to: String,
    val body: String,
    val from: String? = null,
    val messagingServiceSid: String? = null,
)

data class TwilioSmsMessageResponse(
    val sid: String? = null,
    val status: String? = null,
    val errorCode: Int? = null,
)

/** Minimal Twilio Programmable Messaging client. */
open class TwilioSmsClient(
    private val configuration: TwilioSmsConfiguration,
    client: OkHttpClient = RelayHttpClient.clientFor(configuration.normalizedBaseUrl, configuration.tls.certificatePins),
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.normalizedBaseUrl, client, executeOverride) {

    override val providerName: String = "Twilio"

    override fun authorizationHeaders(): Map<String, String> = mapOf("Authorization" to configuration.authorizationHeader)

    open fun send(request: TwilioSmsSendRequest): TwilioSmsMessageResponse {
        val form = FormBody.Builder().add("To", request.to).add("Body", request.body)
        request.from?.let { form.add("From", it) }
        request.messagingServiceSid?.let { form.add("MessagingServiceSid", it) }

        val httpRequest = post(url("2010-04-01", "Accounts", configuration.accountSid, "Messages.json"), form.build())
        val response = executeSuccessful(httpRequest, "SMS send")!!
        val parsed = parse(response)
        if (parsed.sid.isNullOrBlank()) {
            throw ProviderException("Twilio SMS send succeeded without returning a message SID")
        }
        return parsed
    }

    open fun getMessage(messageSid: String): TwilioSmsMessageResponse? {
        val normalized = messageSid.trim()
        if (normalized.isBlank()) {
            return null
        }
        val httpRequest = get(url("2010-04-01", "Accounts", configuration.accountSid, "Messages", "$normalized.json"))
        val response = executeSuccessful(httpRequest, "SMS status lookup", notFoundAsNull = true) ?: return null
        return parse(response)
    }

    private fun parse(response: HttpResponse): TwilioSmsMessageResponse {
        val json = response.jsonBody()
        return TwilioSmsMessageResponse(
            sid = json.string("sid"),
            status = json.string("status"),
            errorCode = json["error_code"].int ?: json["errorCode"].int,
        )
    }
}
