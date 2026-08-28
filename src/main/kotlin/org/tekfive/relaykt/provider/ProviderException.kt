package org.tekfive.relaykt.provider

import org.tekfive.relaykt.RelayException

/**
 * Raised by provider clients for API-level failures. [statusCode] is the HTTP status when the
 * failure happened at the HTTP layer, or null for failures carried inside a 2xx response (for
 * example Slack's `ok: false`) and for non-HTTP failures.
 *
 * [org.tekfive.relaykt.Relay] converts these into [org.tekfive.relaykt.RelayException] and uses
 * [statusCode] to classify retryability, so providers never have to reason about retries.
 *
 * Messages are persisted and logged: never include response bodies, addresses, content, or credentials.
 */
class ProviderException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** Whether this failure is transient (HTTP 408/429/5xx) and therefore worth retrying. */
    val isTransient: Boolean
        get() = statusCode != null && RelayException.isRecoverableStatus(statusCode)
}
