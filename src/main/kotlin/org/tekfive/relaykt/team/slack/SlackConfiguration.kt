package org.tekfive.relaykt.team.slack

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls
import org.tekfive.relaykt.tls.TlsConfiguration

/** Endpoint configuration for [SlackProvider]. Not a `data class` so [botToken] never prints. */
class SlackConfiguration(
    val botToken: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
    val tls: TlsConfiguration = TlsConfiguration(),
) : ToJsonObject {

    init {
        require(botToken.isNotBlank()) { "Slack botToken is required" }
        SecureUrls.requireHttps(normalizedBaseUrl, "Slack baseUrl")
        tls.validateForUrl(normalizedBaseUrl, "Slack baseUrl")
    }

    val authorizationHeader: String
        get() = "Bearer $botToken"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "SlackConfiguration(botToken=REDACTED, baseUrl=$baseUrl, tls=$tls)"

    companion object : FromJsonObject<SlackConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://slack.com"
    }
}
