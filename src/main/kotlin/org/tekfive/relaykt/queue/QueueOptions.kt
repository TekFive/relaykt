package org.tekfive.relaykt.queue

/**
 * Delivery options for [org.tekfive.relaykt.Relay.enqueue].
 *
 * @property label short application-defined tag (e.g. `"appointment-reminder"`) for reporting.
 * @property description free-text note stored with the queued message.
 * @property deliverAfter epoch millis before which the message must not be delivered.
 * @property maxAttempts total delivery attempts allowed before the message is marked FAILED.
 * @property trackReceipt whether to create delivery receipts and poll the provider for status
 *   (only effective with providers supporting [org.tekfive.relaykt.Capability.STATUS_LOOKUP]).
 * @property maxReceiptWaitMinutes how long receipts may stay WAITING before they time out; null
 *   uses [UpdateDeliveryReceiptsJob.defaultMaxReceiptWaitMinutesAck].
 */
data class QueueOptions(
    val label: String = "",
    val description: String? = null,
    val deliverAfter: Long? = null,
    val maxAttempts: Int = 3,
    val trackReceipt: Boolean = false,
    val maxReceiptWaitMinutes: Int? = null,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(maxReceiptWaitMinutes == null || maxReceiptWaitMinutes > 0) { "maxReceiptWaitMinutes must be positive" }
    }
}
