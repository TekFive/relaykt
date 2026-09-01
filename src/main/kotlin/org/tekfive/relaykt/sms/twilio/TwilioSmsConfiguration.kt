package org.tekfive.relaykt.sms.twilio

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls
import org.tekfive.relaykt.tls.TlsConfiguration
import java.util.Base64

/**
 * Endpoint configuration for [TwilioSmsProvider]. Exactly one of [messagingServiceSid] or
 * [fromNumber] must be set; a message-level `from` address overrides [fromNumber].
 *
 * Not a `data class` so a generated `toString()` cannot leak [authToken].
 */
class TwilioSmsConfiguration(
    val accountSid: String,
    val authToken: String,
    val fromNumber: String? = null,
    val messagingServiceSid: String? = null,
    val baseUrl: String? = DEFAULT_BASE_URL,
    val tls: TlsConfiguration = TlsConfiguration(),
) : ToJsonObject {

    init {
        require(accountSid.isNotBlank()) { "Twilio accountSid is required" }
        require(authToken.isNotBlank()) { "Twilio authToken is required" }
        require(!(normalizedFromNumber != null && normalizedMessagingServiceSid != null)) {
            "Twilio configuration cannot specify both messagingServiceSid and fromNumber"
        }
        SecureUrls.requireHttps(normalizedBaseUrl, "Twilio baseUrl")
        tls.validateForUrl(normalizedBaseUrl, "Twilio baseUrl")
    }

    val normalizedFromNumber: String?
        get() = fromNumber?.trim()?.takeIf { it.isNotBlank() }

    val normalizedMessagingServiceSid: String?
        get() = messagingServiceSid?.trim()?.takeIf { it.isNotBlank() }

    val authorizationHeader: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray(Charsets.UTF_8))

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "TwilioSmsConfiguration(accountSid=$accountSid, authToken=REDACTED, baseUrl=$baseUrl, tls=$tls)"

    companion object : FromJsonObject<TwilioSmsConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://api.twilio.com"
    }
}
