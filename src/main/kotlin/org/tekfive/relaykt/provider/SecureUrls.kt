package org.tekfive.relaykt.provider

import java.net.URI
import java.net.URISyntaxException

/** Validation for provider base URLs that receive credentials. */
object SecureUrls {

    /**
     * Requires [url] to use https so credentials are never sent in plaintext. Loopback hosts
     * (`localhost`, `127.0.0.1`, `::1`) are exempt for local test servers. Returns the URL with any
     * trailing slash removed.
     */
    fun requireHttps(url: String, description: String): String {
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("$description is not a valid URL", e)
        }
        val host = uri.host
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        require(uri.scheme == "https" || (uri.scheme == "http" && isLoopback)) {
            "$description must use https (credentials are sent with every request)"
        }
        return url.trim().trimEnd('/')
    }
}
