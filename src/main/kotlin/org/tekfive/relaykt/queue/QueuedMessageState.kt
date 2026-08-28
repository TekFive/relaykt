package org.tekfive.relaykt.queue

import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType

enum class QueuedMessageState(
    override val id: Int,
    override val displayName: String,
    val completed: Boolean,
    val description: String,
) : DataEnum {
    QUEUED(1, "Queued", false, "Message has been persisted and is waiting for the queue processor."),
    PENDING(2, "Pending", false, "A delivery job has been scheduled, but has not started yet."),
    PROCESSING(3, "Processing", false, "A delivery job is currently attempting to deliver the message."),
    WAITING_TO_RETRY(4, "Waiting To Retry", false, "A delivery attempt failed with a recoverable error; another attempt is scheduled."),
    SENT(5, "Sent", true, "Message was handed to the provider successfully."),
    TIMED_OUT(6, "Timed Out", true, "Delivery stalled and its outcome is unknown; no further attempts will be made."),
    FAILED(7, "Failed", true, "Message delivery failed, and no further attempts will be made."),
    CANCELLED(8, "Cancelled", true, "Message delivery was cancelled before it completed."),
    ;

    companion object : DataEnumColumnType<QueuedMessageState>()
}
