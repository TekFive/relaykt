package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.tekfive.ack.Ack
import org.tekfive.ack.ackNamespace
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import kotlin.time.Duration.Companion.days

/**
 * Scheduled job that deletes completed queued messages once they are older than the configured
 * retention. Sent messages and failed/timed-out/cancelled messages have separate retention
 * periods. Age is measured from [QueuedMessageTable.lastStateChangeAt]; deleting a queued
 * message cascades to its attempts and receipts.
 */
class CleanQueuedMessagesJob : Job {

    companion object : FixedIntervalJobSpec {

        override val estimateRuntime: Boolean = false

        override val exclusiveExecution: Boolean = true

        override val defaultInternalSeconds: Long = 24L * 60 * 60

        override val intervalSecondsProperty: Ack<Long>
            get() = Ack.long("FIXED_INTERVAL_SECONDS", defaultInternalSeconds, min = 1L, namespace = ackNamespace(getNamespaceClass()), description = "Interval in seconds between queued-message cleanup runs.")

        val sentKeepDaysAck = Ack.int("CLEAN_SENT_KEEP_DAYS", 90, min = 0, namespace = MessageQueueProcessor.NAMESPACE, description = "Age in days after which successfully sent queued messages are deleted.")

        val failedKeepDaysAck = Ack.int("CLEAN_FAILED_KEEP_DAYS", fallback = sentKeepDaysAck, min = 0, namespace = MessageQueueProcessor.NAMESPACE, description = "Age in days after which failed, timed out, or cancelled queued messages are deleted. Defaults to the sent retention.")

        override fun createJob(): Job = CleanQueuedMessagesJob()

        private val failedStates = listOf(QueuedMessageState.FAILED, QueuedMessageState.TIMED_OUT, QueuedMessageState.CANCELLED)
    }

    override fun execute(context: JobContext): JobResult {
        val now = System.currentTimeMillis()
        val sentCutoffAt = now - sentKeepDaysAck().days.inWholeMilliseconds
        val failedCutoffAt = now - failedKeepDaysAck().days.inWholeMilliseconds

        val (sentDeleted, failedDeleted) = db {
            val sent = QueuedMessageTable.deleteWhere {
                (state eq QueuedMessageState.SENT) and (lastStateChangeAt lessEq sentCutoffAt)
            }
            val failed = QueuedMessageTable.deleteWhere {
                (state inList failedStates) and (lastStateChangeAt lessEq failedCutoffAt)
            }
            sent to failed
        }

        return JobCompleted("Deleted $sentDeleted sent and $failedDeleted failed queued messages.")
    }
}
