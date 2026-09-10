package org.tekfive.relaykt.tls

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.net.ssl.SSLContext

/** Applies the shared HTTP/SMTP trust rules, retaining OkHttp's hostname verification. */
object TlsHttpClient {
    fun configure(builder: OkHttpClient.Builder, baseUrl: String, tls: TlsConfiguration): OkHttpClient.Builder {
        if (!tls.customTrustEnabled) {
            return builder
        }
        val url = baseUrl.toHttpUrl()
        require(url.isHttps) { "Custom TLS settings require an https URL" }

        // Enforce pins during the handshake, including pins used as trust anchors.
        val trust = TlsCertificatePins.trustManager(tls.certificatePins, tls.caCertificate)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), null)
        builder.sslSocketFactory(context.socketFactory, trust)

        // Custom trust must not follow a redirect to a different service or plaintext URL.
        return builder.followRedirects(false).followSslRedirects(false)
    }
}
