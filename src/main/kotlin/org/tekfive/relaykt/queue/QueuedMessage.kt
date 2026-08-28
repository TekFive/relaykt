package org.tekfive.relaykt.queue

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.Data
import org.tekfive.keep.db.dbTransactionAt
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.endpoint.Endpoint

/**
 * A message persisted for asynchronous delivery. The original [Message] is stored as JSON in
 * [payload] and restored with [message]; [recipients] is denormalized for reporting.
 */
class QueuedMessage(
    val channel: Channel,
    val endpointId: String,
    val recipients: List<String>,
    val payload: JsonObject,
    val label: String = "",
    val description: String? = null,
    val trackReceipt: Boolean = false,
    val deliverAfter: Long? = null,
    val maxAttempts: Int = 1,
    val maxReceiptWaitMinutes: Int? = null,
    val createdAt: Long = dbTransactionAt(),
    internal var _state: QueuedMessageState = QueuedMessageState.QUEUED,
    var lastStateChangeAt: Long = createdAt,
    var nextAttemptAt: Long? = null,
    var attemptCount: Int = 0,
    var providerMessageId: String? = null,
) : Data() {

    constructor(message: Message, endpoint: Endpoint, options: QueueOptions) : this(
        channel = message.channel,
        endpointId = endpoint.id,
        recipients = message.allRecipients.map { it.address },
        payload = message.toJsonObject(),
        label = options.label,
        description = options.description,
        trackReceipt = options.trackReceipt,
        deliverAfter = options.deliverAfter,
        maxAttempts = options.maxAttempts,
        maxReceiptWaitMinutes = options.maxReceiptWaitMinutes,
    )

    var state: QueuedMessageState
        get() = _state
        set(value) {
            _state = value
            lastStateChangeAt = System.currentTimeMillis()
        }

    val message: Message
        get() = channel.readMessage(payload)

    val hasAttemptsRemaining: Boolean
        get() = attemptCount < maxAttempts
}
