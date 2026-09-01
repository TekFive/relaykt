package org.tekfive.relaykt.email.sendgrid

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls
import org.tekfive.relaykt.tls.TlsConfiguration

/** Endpoint configuration for [SendGridProvider]. */
data class SendGridConfiguration(
    val apiKey: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
    val tls: TlsConfiguration = TlsConfiguration(),
) : ToJsonObject {

    init {
        require(apiKey.isNotBlank()) { "SendGrid apiKey is required" }
        SecureUrls.requireHttps(normalizedBaseUrl, "SendGrid baseUrl")
        tls.validateForUrl(normalizedBaseUrl, "SendGrid baseUrl")
    }

    val authorizationHeader: String
        get() = "Bearer $apiKey"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "SendGridConfiguration(apiKey=REDACTED, baseUrl=$baseUrl, tls=$tls)"

    companion object : FromJsonObject<SendGridConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://api.sendgrid.com"
    }
}
