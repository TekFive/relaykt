package org.tekfive.relaykt

/**
 * Failure raised by RelayKt when a message cannot be sent.
 *
 * [recoverable] drives the queue's retry decision: transient failures (network errors, HTTP 408/429/5xx)
 * are retried up to the queued message's attempt limit; everything else fails permanently.
 *
 * Messages of this exception are persisted in delivery attempts and written to logs, so they must
 * never contain recipient addresses, message content, provider response bodies, or credentials.
 */
open class RelayException(
    message: String,
    val recoverable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        /**
         * Whether an HTTP status returned by a provider represents a transient failure that is safe
         * to retry: request timeout, rate limiting, or a server-side error.
         */
        fun isRecoverableStatus(statusCode: Int): Boolean {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500
        }
    }
}

/** A message asked for something the selected provider cannot do (see [Capability]). */
class UnsupportedCapabilityException(
    val capability: Capability,
    providerId: String,
) : RelayException("Provider '$providerId' does not support ${capability.displayName}", recoverable = false)
