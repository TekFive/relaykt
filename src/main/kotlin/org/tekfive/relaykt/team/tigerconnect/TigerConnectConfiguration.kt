package org.tekfive.relaykt.team.tigerconnect

import okhttp3.Credentials
import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls
import org.tekfive.relaykt.tls.TlsConfiguration

/** Endpoint configuration for [TigerConnectProvider]. Not a `data class` so secrets never print. */
class TigerConnectConfiguration(
    val apiKey: String,
    val apiSecret: String,
    val baseUrl: String? = DEFAULT_BASE_URL,
    val tls: TlsConfiguration = TlsConfiguration(),
) : ToJsonObject {

    init {
        require(apiKey.isNotBlank()) { "TigerConnect apiKey is required" }
        require(apiSecret.isNotBlank()) { "TigerConnect apiSecret is required" }
        SecureUrls.requireHttps(normalizedBaseUrl, "TigerConnect baseUrl")
        tls.validateForUrl(normalizedBaseUrl, "TigerConnect baseUrl")
    }

    val authorizationHeader: String
        get() = Credentials.basic(apiKey, apiSecret)

    val normalizedBaseUrl: String
        get() = (baseUrl ?: DEFAULT_BASE_URL).trimEnd('/')

    override fun toString(): String = "TigerConnectConfiguration(apiKey=REDACTED, apiSecret=REDACTED, baseUrl=$baseUrl, tls=$tls)"

    companion object : FromJsonObject<TigerConnectConfiguration> {
        const val DEFAULT_BASE_URL = "https://api.tigertext.me"
    }
}
