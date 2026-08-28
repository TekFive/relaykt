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
 * Background poller that turns ready queued messages into [SendQueuedMessageJob]s and recovers
 * messages stuck in PENDING/PROCESSING.
 *
 * Runs on a single daemon thread started with [start]. [processOnce] is the unit of work and can
 * be called directly (tests, or applications that schedule their own polling).
 */
object MessageQueueProcessor : Runnable {

    private val log = LoggerFactory.getLogger(MessageQueueProcessor::class.java)

    val pollSleepSecondsAck = Ack.int("POLL_SLEEP_SECONDS", 20, min = 1, namespace = NAMESPACE, description = "Seconds the message queue processor sleeps between polls when no work was found.")

    val maxPendingMinutesAck = Ack.int("MAX_PENDING_MINUTES", 30, min = 1, namespace = NAMESPACE, description = "Minutes a message may stay in PENDING or PROCESSING before being considered stalled.")

    val batchSizeAck = Ack.int("BATCH_SIZE", 100, min = 1, namespace = NAMESPACE, description = "Maximum ready messages dispatched per poll.")

    @Volatile
    private var processThread: Thread? = null

    private val lock = Any()

    fun start() {
        synchronized(lock) {
            if (processThread?.isAlive != true) {
                processThread = Thread(this, "RelayKt-MessageQueueProcessor").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun stop(joinTimeoutSeconds: Int = 15) {
        val thread: Thread?
        synchronized(lock) {
            thread = processThread
            processThread = null
        }
        if (thread != null) {
            thread.interrupt()
            thread.join(joinTimeoutSeconds * 1000L)
        }
    }

    val isRunning: Boolean
        get() = processThread?.isAlive == true

    override fun run() {
        while (processThread == Thread.currentThread()) {
            var workDone = false
            try {
                workDone = processOnce() > 0
            } catch (e: Exception) {
                log.error("Message queue processor failed while processing queued messages.", e)
            }
            if (processThread == Thread.currentThread() && !workDone) {
                try {
                    Thread.sleep(pollSleepSecondsAck() * 1000L)
                } catch (e: InterruptedException) {
                    // Interrupt is the stop signal; the loop condition decides whether to exit.
                    log.debug("Message queue processor poll sleep interrupted.")
                }
            }
        }
    }

    /**
     * Dispatches every ready message and recovers stalled ones. Returns the number of messages
     * acted on (dispatched or recovered).
     */
    fun processOnce(now: Long = System.currentTimeMillis()): Int {
        return dispatchReadyMessages(now) + recoverStalledMessages(now)
    }

    internal fun dispatchReadyMessages(now: Long): Int {
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

    internal fun recoverStalledMessages(now: Long): Int {
        var recovered = 0
        val cutoffAt = now - maxPendingMinutesAck() * 60_000L
        db {
            val stalled = QueuedMessageTable
                .select(QueuedMessageTable.id, QueuedMessageTable.state)
                .where {
                    (QueuedMessageTable.state inList listOf(QueuedMessageState.PENDING, QueuedMessageState.PROCESSING)) and
                        (QueuedMessageTable.lastStateChangeAt lessEq cutoffAt)
                }
                .map { row -> row[QueuedMessageTable.id] to row[QueuedMessageTable.state] }

            for ((queuedMessageId, state) in stalled) {
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
