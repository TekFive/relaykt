package org.tekfive.relaykt.http

/** Fully-read HTTP response used by provider clients and by tests that stub HTTP calls. */
data class HttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean
        get() = code in 200..299

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
