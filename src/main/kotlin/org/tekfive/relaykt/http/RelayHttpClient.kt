package org.tekfive.relaykt.http

import okhttp3.OkHttpClient
import org.tekfive.ack.Ack
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client for provider integrations. One [OkHttpClient] shares a connection pool and
 * dispatcher across all providers (OkHttp's documented best practice). Providers needing different
 * settings should derive from [client] with [OkHttpClient.newBuilder], which reuses the pool.
 */
object RelayHttpClient {

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

    const val NAMESPACE = "RELAY"
}
