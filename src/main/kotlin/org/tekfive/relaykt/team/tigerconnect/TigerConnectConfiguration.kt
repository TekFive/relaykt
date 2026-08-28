package org.tekfive.relaykt.team.tigerconnect

import okhttp3.Credentials
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls

/** Endpoint configuration for [TigerConnectProvider]. Not a `data class` so secrets never print. */
class TigerConnectConfiguration(
    val apiKey: String,
    val apiSecret: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
) : ToJsonObject {

    init {
        require(apiKey.isNotBlank()) { "TigerConnect apiKey is required" }
        require(apiSecret.isNotBlank()) { "TigerConnect apiSecret is required" }
        SecureUrls.requireHttps(normalizedBaseUrl, "TigerConnect baseUrl")
    }

    val authorizationHeader: String
        get() = Credentials.basic(apiKey, apiSecret)

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "TigerConnectConfiguration(apiKey=REDACTED, apiSecret=REDACTED, baseUrl=$baseUrl)"

    companion object : FromJsonObject<TigerConnectConfiguration> {
        const val DEFAULT_BASE_URL = "https://api.tigertext.me"
    }
}
