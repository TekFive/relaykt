package org.tekfive.relaykt.tls

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.net.ssl.SSLContext

/** Applies CA trust and optional pins together, retaining OkHttp's hostname verification. */
object TlsHttpClient {
    fun configure(builder: OkHttpClient.Builder, baseUrl: String, tls: TlsConfiguration): OkHttpClient.Builder {
        if (!tls.customTrustEnabled) {
            return builder
        }
        val url = baseUrl.toHttpUrl()
        require(url.isHttps) { "Custom TLS settings require an https URL" }
        if (!tls.caCertificate.isNullOrBlank()) {
            val trust = TlsCertificates.trustManager(tls.caCertificate)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trust), null)
            builder.sslSocketFactory(context.socketFactory, trust)
        }
        if (tls.certificatePinningEnabled) {
            builder.certificatePinner(CertificatePinner.Builder()
                .add(url.host, *TlsCertificatePins.normalize(tls.certificatePins).toTypedArray())
                .build())
        }
        // Custom trust must not follow a redirect to a different service or plaintext URL.
        return builder.followRedirects(false).followSslRedirects(false)
    }
}
