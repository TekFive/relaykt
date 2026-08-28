package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.tekfive.keep.db.db
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.endpoint.Endpoint

/**
 * Persistence-facing operations on the delivery queue. [org.tekfive.relaykt.Relay.enqueue] is the
 * usual entry point; the functions here are for applications that need to inspect or cancel
 * queued messages.
 */
object MessageQueue {

    fun enqueue(message: Message, endpoint: Endpoint, options: QueueOptions = QueueOptions()): Long {
        return db {
            QueuedMessageTable.create(QueuedMessage(message, endpoint, options)).id
        }
    }

    fun find(queuedMessageId: Long): QueuedMessage? = db { QueuedMessageTable.findById(queuedMessageId) }

    fun attempts(queuedMessageId: Long): List<DeliveryAttempt> = db {
        DeliveryAttemptTable.findWhere(DeliveryAttemptTable.queuedMessageId eq queuedMessageId)
    }

    fun receipts(queuedMessageId: Long): List<DeliveryReceipt> = db {
        DeliveryReceiptTable.findWhere(DeliveryReceiptTable.queuedMessageId eq queuedMessageId)
    }

    /** States a message can be cancelled from: no send is in flight and the message is not terminal. */
    val cancellableStates: List<QueuedMessageState> = listOf(QueuedMessageState.QUEUED, QueuedMessageState.PENDING, QueuedMessageState.WAITING_TO_RETRY)

    fun isCancellable(state: QueuedMessageState): Boolean = state in cancellableStates

    /**
     * Cancels a message that has not started sending and returns the state it was cancelled from,
     * or null when it could not be cancelled (unknown id, already processing, or completed).
     *
     * A PENDING message may be cancelled: its delivery job claims the message only from PENDING,
     * so once the row reads CANCELLED the job simply fails without sending. The guarded update
     * makes the race safe in the other direction too — if the job claims first, the cancel returns null.
     */
    fun cancel(queuedMessageId: Long): QueuedMessageState? {
        return db {
            val current = QueuedMessageTable.findById(queuedMessageId)?.state ?: return@db null
            if (!isCancellable(current)) {
                return@db null
            }
            val updated = QueuedMessageTable.update({
                (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq current)
            }) { statement ->
                statement[QueuedMessageTable.state] = QueuedMessageState.CANCELLED
                statement[QueuedMessageTable.lastStateChangeAt] = System.currentTimeMillis()
                statement[QueuedMessageTable.nextAttemptAt] = null
            }
            if (updated == 1) current else null
        }
    }
}
