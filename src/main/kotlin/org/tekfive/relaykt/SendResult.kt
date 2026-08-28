package org.tekfive.relaykt

/**
 * Outcome of a successful provider send.
 *
 * @property messageId provider-assigned identifier (may be empty when the provider returns none,
 *   e.g. SMTP). Used later for [org.tekfive.relaykt.Relay.status] when the provider supports
 *   [Capability.STATUS_LOOKUP].
 * @property providerId id of the provider that performed the send.
 * @property status best-known status immediately after the send.
 */
data class SendResult(
    val messageId: String,
    val providerId: String,
    val status: DeliveryStatus = DeliveryStatus.SENT,
    /**
     * Provider message id per recipient address, for providers that perform one send per recipient
     * (Slack, TigerConnect). Empty when a single send covered every recipient; [messageId] is then
     * the id for all of them.
     */
    val recipientMessageIds: Map<String, String> = emptyMap(),
) {
    val hasMessageId: Boolean
        get() = messageId.isNotBlank()

    /** The id to track [recipientAddress] with: its own id when known, otherwise [messageId]. */
    fun messageIdFor(recipientAddress: String): String = recipientMessageIds[recipientAddress] ?: messageId
}
