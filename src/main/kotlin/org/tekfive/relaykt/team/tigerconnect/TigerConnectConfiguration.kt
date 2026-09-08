package org.tekfive.relaykt.team.tigerconnect

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    val organizationId: String? = null,
    /** Sending to a role also requires the authenticated sender's user token. */
    val senderUserId: String? = null,
) : ToJsonObject {

    init {
        require(apiKey.isNotBlank()) { "TigerConnect apiKey is required" }
        require(apiSecret.isNotBlank()) { "TigerConnect apiSecret is required" }
        require(organizationId == null || organizationId.isNotBlank()) { "TigerConnect organizationId must not be blank" }
        require(senderUserId == null || senderUserId.isNotBlank()) { "TigerConnect senderUserId must not be blank" }
        SecureUrls.requireHttps(normalizedBaseUrl, "TigerConnect baseUrl")
        tls.validateForUrl(normalizedBaseUrl, "TigerConnect baseUrl")
    }

    val authorizationHeader: String
        get() = Credentials.basic(apiKey, apiSecret)

    val normalizedBaseUrl: String
        get() {
            val url = (baseUrl ?: DEFAULT_BASE_URL).trim().toHttpUrl()
            require(url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) {
                "TigerConnect baseUrl must not include credentials, a query or a fragment"
            }
            return (if (url.encodedPath == "/") url.newBuilder().addPathSegment("v2").build() else url)
                .toString().trimEnd('/')
        }

    override fun toString(): String = "TigerConnectConfiguration(apiKey=REDACTED, apiSecret=REDACTED, baseUrl=$baseUrl, tls=$tls)"

    companion object : FromJsonObject<TigerConnectConfiguration> {
        const val DEFAULT_BASE_URL = "https://api.tigertext.me/v2"
        /** Published by the official SDK; access must be provisioned by TigerConnect. */
        const val UAT_BASE_URL = "https://uat-devapi.tigertext.me/v2"
    }
}
