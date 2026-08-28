package org.tekfive.relaykt.team.slack

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls

/** Endpoint configuration for [SlackProvider]. Not a `data class` so [botToken] never prints. */
class SlackConfiguration(
    val botToken: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) : ToJsonObject {

    init {
        require(botToken.isNotBlank()) { "Slack botToken is required" }
        SecureUrls.requireHttps(normalizedBaseUrl, "Slack baseUrl")
    }

    val authorizationHeader: String
        get() = "Bearer $botToken"

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "SlackConfiguration(botToken=REDACTED, baseUrl=$baseUrl)"

    companion object : FromJsonObject<SlackConfiguration> {
        const val DEFAULT_BASE_URL: String = "https://slack.com"
    }
}
