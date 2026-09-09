package org.tekfive.relaykt.http

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.tekfive.ack.Ack
import org.tekfive.relaykt.tls.TlsConfiguration
import org.tekfive.relaykt.tls.TlsHttpClient
import org.tekfive.relaykt.tls.TlsCertificatePins
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client for provider integrations. One [OkHttpClient] shares a connection pool and
 * dispatcher across all providers (OkHttp's documented best practice). Providers needing different
 * settings should derive from [client] with [OkHttpClient.newBuilder], which reuses the pool.
 */
object RelayHttpClient {

    private data class PinnedClientKey(val host: String, val pins: List<String>, val caCertificate: String?)

    private val pinnedClients = ConcurrentHashMap<PinnedClientKey, OkHttpClient>()

    val connectTimeoutSecondsAck = Ack.int("HTTP_CONNECT_TIMEOUT_SECONDS", 10, min = 1, namespace = NAMESPACE, description = "Connect timeout in seconds for provider HTTP calls.")

    val readTimeoutSecondsAck = Ack.int("HTTP_READ_TIMEOUT_SECONDS", 30, min = 1, namespace = NAMESPACE, description = "Read timeout in seconds for provider HTTP calls.")

    val callTimeoutSecondsAck = Ack.int("HTTP_CALL_TIMEOUT_SECONDS", 60, min = 1, namespace = NAMESPACE, description = "Total call timeout in seconds for provider HTTP calls.")

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSecondsAck().toLong(), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSecondsAck().toLong(), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSecondsAck().toLong(), TimeUnit.SECONDS)
            .callTimeout(callTimeoutSecondsAck().toLong(), TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns the shared client when no pins are configured, otherwise a cached client that reuses
     * the shared connection pool and requires one of [pins] for the exact host in [baseUrl].
     */
    fun clientFor(baseUrl: String, pins: List<String>): OkHttpClient {
        return clientFor(baseUrl, TlsConfiguration(pins))
    }

    fun clientFor(baseUrl: String, tls: TlsConfiguration): OkHttpClient {
        val normalizedPins = TlsCertificatePins.normalize(tls.certificatePins)
        if (!tls.customTrustEnabled) {
            return client
        }
        val url = baseUrl.toHttpUrl()
        require(url.isHttps) { "Custom TLS settings require an https base URL" }
        val key = PinnedClientKey(url.host, normalizedPins, tls.caCertificate?.trim())
        return pinnedClients.computeIfAbsent(key) {
            TlsHttpClient.configure(client.newBuilder(), baseUrl, tls).build()
        }
    }

    const val NAMESPACE = "RELAY"
}
