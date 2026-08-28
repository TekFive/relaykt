package org.tekfive.relaykt.team

import org.tekfive.relaykt.RelayException

/** Helpers shared by team providers that fan one message out to several recipients. */
object TeamMessageSupport {

    private const val MULTI_PREFIX = "multi:"

    /** Joins per-recipient provider ids into one id that [splitMessageIds] can reverse. */
    fun aggregateMessageIds(messageIds: List<String>): String {
        return if (messageIds.size == 1) messageIds.single() else MULTI_PREFIX + messageIds.joinToString(",")
    }

    fun splitMessageIds(messageId: String): List<String> {
        return if (messageId.startsWith(MULTI_PREFIX)) {
            messageId.removePrefix(MULTI_PREFIX).split(",").filter { it.isNotBlank() }
        } else {
            listOf(messageId)
        }
    }

    /**
     * Once at least one recipient has received the message a retry would duplicate delivery, so the
     * failure becomes non-recoverable. With nothing sent yet the original exception drives retry
     * classification.
     */
    fun partialFailure(providerName: String, cause: Exception, sentCount: Int, totalCount: Int): Exception {
        if (sentCount == 0) {
            return cause
        }
        return RelayException("$providerName send failed after sending to $sentCount of $totalCount recipients", recoverable = false, cause = cause)
    }
}
