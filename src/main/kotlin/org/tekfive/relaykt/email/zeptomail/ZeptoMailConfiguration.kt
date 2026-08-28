package org.tekfive.relaykt.email.zeptomail

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls

/**
 * Endpoint configuration for [ZeptoMailProvider]. [sendMailToken] authorizes sending; the optional
 * [oauthAccessToken] is required for status lookup. [bounceAddress], [trackOpens], and
 * [trackClicks] are passed through to the send API when set.
 */
data class ZeptoMailConfiguration(
    val sendMailToken: String,
    val oauthAccessToken: String? = null,
    val baseUrl: String? = DEFAULT_BASE_URL,
    val bounceAddress: String? = null,
    val trackOpens: Boolean? = null,
    val trackClicks: Boolean? = null,
) : ToJsonObject {

    init {
        require(sendMailToken.isNotBlank()) { "ZeptoMail sendMailToken is required" }
        SecureUrls.requireHttps(normalizedBaseUrl, "ZeptoMail baseUrl")
    }

    val sendAuthorizationHeader: String
        get() = "Zoho-enczapikey $sendMailToken"

    val oauthAuthorizationHeader: String?
        get() = oauthAccessToken?.trim()?.takeIf { it.isNotBlank() }?.let { "Zoho-oauthtoken $it" }

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "ZeptoMailConfiguration(sendMailToken=REDACTED, oauthAccessToken=REDACTED, baseUrl=$baseUrl)"

    companion object : FromJsonObject<ZeptoMailConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://api.zeptomail.com"
    }
}
