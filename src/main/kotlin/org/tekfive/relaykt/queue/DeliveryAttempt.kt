package org.tekfive.relaykt.queue

import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.fkey
import org.tekfive.keep.data.timestamp
import org.tekfive.keep.db.dbTransactionAt

enum class DeliveryAttemptState(
    override val id: Int,
    override val displayName: String,
    val description: String,
) : DataEnum {
    SENDING(1, "Sending", "The delivery attempt is in progress."),
    SENT(2, "Sent", "The delivery attempt completed successfully."),
    FAILED(3, "Failed", "The delivery attempt failed."),
    ;

    companion object : DataEnumColumnType<DeliveryAttemptState>()
}

/** One try at delivering a [QueuedMessage]; [details] holds the scrubbed failure message. */
class DeliveryAttempt(
    val queuedMessageId: Long,
    var state: DeliveryAttemptState,
    var details: String? = null,
    var recoverable: Boolean? = null,
    val startedAt: Long = dbTransactionAt(),
    var endedAt: Long? = null,
) : Data()

object DeliveryAttemptTable : DataTable<DeliveryAttempt>("relay_delivery_attempts") {
    val queuedMessageId = fkey("queued_message_id", QueuedMessageTable)
    val state = dataEnum<DeliveryAttemptState>("state_id")
    val details = text("details").nullable()
    val recoverable = bool("recoverable").nullable()
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at").nullable()
}
