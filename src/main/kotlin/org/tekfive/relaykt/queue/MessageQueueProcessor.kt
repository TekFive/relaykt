package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import org.tekfive.ack.Ack
import org.tekfive.jfk.json
import org.tekfive.keep.db.db
import org.tekfive.keep.db.dbCommit
import org.tekfive.keep.job.db.JobRecordsTable

/**
 * Claims ready messages and recovers stalled deliveries for [DispatchQueuedMessagesJob].
 * Each claim and its delivery job commit together, so interrupted sweeps can resume safely.
 */
object MessageQueueProcessor {

    private val log = LoggerFactory.getLogger(MessageQueueProcessor::class.java)

    val maxPendingMinutesAck = Ack.int("MAX_PENDING_MINUTES", 30, min = 1, namespace = NAMESPACE, description = "Minutes a message may stay in PENDING or PROCESSING before being considered stalled.")

    val batchSizeAck = Ack.int("BATCH_SIZE", 100, min = 1, namespace = NAMESPACE, description = "Maximum messages loaded per dispatch or recovery batch. Each sweep drains successive batches.")

    /**
     * Dispatches and recovers one batch each. The callback lets Keep check cancellation and
     * heartbeat before each claim; failures propagate to the scheduled job.
     */
    fun processOnce(now: Long = System.currentTimeMillis(), checkIn: () -> Unit = {}): Int {
        return dispatchReadyMessages(now, checkIn) + recoverStalledMessages(now, checkIn)
    }

    internal fun dispatchReadyMessages(now: Long, checkIn: () -> Unit = {}): Int {
        var dispatched = 0
        db {
            val ready = QueuedMessageTable
                .select(QueuedMessageTable.id, QueuedMessageTable.state)
                .where {
                    ((QueuedMessageTable.state eq QueuedMessageState.QUEUED) and
                        (QueuedMessageTable.deliverAfter.isNull() or (QueuedMessageTable.deliverAfter lessEq now))) or
                        ((QueuedMessageTable.state eq QueuedMessageState.WAITING_TO_RETRY) and
                            (QueuedMessageTable.nextAttemptAt.isNull() or (QueuedMessageTable.nextAttemptAt lessEq now)))
                }
                .orderBy(QueuedMessageTable.id)
                .limit(batchSizeAck())
                .map { row -> row[QueuedMessageTable.id] to row[QueuedMessageTable.state] }

            for ((queuedMessageId, state) in ready) {
                checkIn()
                // Optimistic state guard: only the poller that flips QUEUED/WAITING_TO_RETRY -> PENDING
                // creates the job, so several processors can share one database safely.
                val updated = QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq state) }) { statement ->
                    statement[QueuedMessageTable.state] = QueuedMessageState.PENDING
                    statement[QueuedMessageTable.lastStateChangeAt] = System.currentTimeMillis()
                }
                if (updated == 1) {
                    JobRecordsTable.insertJob(SendQueuedMessageJob, details = json { SendQueuedMessageJob.QUEUED_MESSAGE_ID_PROPERTY set queuedMessageId })
                    dbCommit()
                    dispatched++
                }
            }
        }
        return dispatched
    }

    internal fun recoverStalledMessages(now: Long, checkIn: () -> Unit = {}): Int {
        var recovered = 0
        val cutoffAt = now - maxPendingMinutesAck() * 60_000L
        db {
            val stalled = QueuedMessageTable
                .select(QueuedMessageTable.id, QueuedMessageTable.state)
                .where {
                    (QueuedMessageTable.state inList listOf(QueuedMessageState.PENDING, QueuedMessageState.PROCESSING)) and
                        (QueuedMessageTable.lastStateChangeAt lessEq cutoffAt)
                }
                .orderBy(QueuedMessageTable.id)
                .limit(batchSizeAck())
                .map { row -> row[QueuedMessageTable.id] to row[QueuedMessageTable.state] }

            for ((queuedMessageId, state) in stalled) {
                checkIn()
                // A stalled PENDING message never started sending, so re-queueing cannot double-send.
                // A stalled PROCESSING message has an unknown send outcome, so it is timed out.
                val newState = if (state == QueuedMessageState.PENDING) QueuedMessageState.QUEUED else QueuedMessageState.TIMED_OUT
                val updated = QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessageId) and (QueuedMessageTable.state eq state) }) { statement ->
                    statement[QueuedMessageTable.state] = newState
                    statement[QueuedMessageTable.lastStateChangeAt] = System.currentTimeMillis()
                }
                if (updated == 1) {
                    log.warn("Queued message {} stalled in {} and was moved to {}.", queuedMessageId, state, newState)
                    dbCommit()
                    recovered++
                }
            }
        }
        return recovered
    }

    const val NAMESPACE = "RELAY_QUEUE"
}
