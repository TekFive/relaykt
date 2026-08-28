package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.tekfive.ack.Ack
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobFailed
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.JobSpec
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.endpoint.Endpoint
import kotlin.math.min
import kotlin.math.pow

/**
 * KEEP job that delivers one [QueuedMessage]. Created by [MessageQueueProcessor]; the job id is
 * carried in the job details under [QUEUED_MESSAGE_ID_PROPERTY].
 *
 * Lifecycle: PENDING -> PROCESSING (attempt recorded) -> SENT | WAITING_TO_RETRY | FAILED. Retry
 * delays grow exponentially from [retryBaseDelaySecondsAck] up to [retryMaxDelaySecondsAck].
 */
class SendQueuedMessageJob : Job {

    companion object : JobSpec {

        const val QUEUED_MESSAGE_ID_PROPERTY = "queuedMessageId"

        val retryBaseDelaySecondsAck = Ack.int("RETRY_BASE_DELAY_SECONDS", 120, min = 0, namespace = MessageQueueProcessor.NAMESPACE, description = "Delay before the first retry of a failed queued message; doubles with each further attempt.")

        val retryMaxDelaySecondsAck = Ack.int("RETRY_MAX_DELAY_SECONDS", 3600, min = 0, namespace = MessageQueueProcessor.NAMESPACE, description = "Upper bound on the delay between retries of a failed queued message.")

        override fun createJob(): Job = SendQueuedMessageJob()

        override val estimateRuntime: Boolean = false

        /** Delay before attempt number `attemptCount + 1`, with exponential backoff. */
        fun retryDelayMillis(attemptCount: Int): Long {
            val base = retryBaseDelaySecondsAck().toDouble()
            val scaled = base * 2.0.pow((attemptCount - 1).coerceAtLeast(0))
            return (min(scaled, retryMaxDelaySecondsAck().toDouble()) * 1000).toLong()
        }

        internal fun buildReceipts(queuedMessage: QueuedMessage, endpoint: Endpoint, message: Message, result: SendResult): List<DeliveryReceipt> {
            return message.allRecipients.map { recipient ->
                DeliveryReceipt(
                    queuedMessageId = queuedMessage.id,
                    endpointId = endpoint.id,
                    providerId = result.providerId,
                    providerMessageId = result.messageIdFor(recipient.address),
                    recipientAddress = recipient.address,
                    lastDeliveryStatus = result.status,
                )
            }
        }
    }

    override fun execute(context: JobContext): JobResult {
        val queuedMessageId = context.details?.get(QUEUED_MESSAGE_ID_PROPERTY)?.long
            ?: return JobFailed("No $QUEUED_MESSAGE_ID_PROPERTY property provided in job details.")

        val queuedMessage = QueuedMessageTable.findById(queuedMessageId)
            ?: return JobFailed("Unable to find queued message with id $queuedMessageId")

        if (queuedMessage.state != QueuedMessageState.PENDING) {
            return JobFailed("Queued message $queuedMessageId is not in PENDING state (state=${queuedMessage.state}).")
        }

        val attempt = claim(queuedMessage) ?: return JobFailed("Unable to claim queued message $queuedMessageId for sending.")

        var result: JobResult = JobCompleted()
        try {
            val endpoint = Relay.resolveEndpoint(queuedMessage.endpointId)
            val message = queuedMessage.message
            val sendResult = Relay.send(message, endpoint)

            queuedMessage.state = QueuedMessageState.SENT
            queuedMessage.providerMessageId = sendResult.messageId.takeIf { it.isNotBlank() }
            attempt.state = DeliveryAttemptState.SENT

            if (shouldTrackReceipt(queuedMessage, endpoint, sendResult)) {
                try {
                    db {
                        for (receipt in buildReceipts(queuedMessage, endpoint, message, sendResult)) {
                            DeliveryReceiptTable.create(receipt)
                        }
                    }
                } catch (e: Exception) {
                    // The message was delivered; a receipt-persistence failure must not flip it to FAILED.
                    context.log.error("Failed to persist delivery receipts for queued message $queuedMessageId", e)
                    result = JobFailed("Failed to persist delivery receipts for queued message $queuedMessageId")
                }
            }
        } catch (e: Exception) {
            val relayException = e as? RelayException
            val recoverable = relayException?.recoverable ?: false
            attempt.state = DeliveryAttemptState.FAILED
            attempt.details = e.message?.take(2000)
            attempt.recoverable = recoverable

            if (recoverable && queuedMessage.hasAttemptsRemaining) {
                queuedMessage.state = QueuedMessageState.WAITING_TO_RETRY
                queuedMessage.nextAttemptAt = System.currentTimeMillis() + retryDelayMillis(queuedMessage.attemptCount)
                context.log.warn("Recoverable failure sending queued message $queuedMessageId; attempt ${queuedMessage.attemptCount} of ${queuedMessage.maxAttempts}.", e)
            } else {
                queuedMessage.state = QueuedMessageState.FAILED
                context.log.error("Failed to send queued message $queuedMessageId after attempt ${queuedMessage.attemptCount} of ${queuedMessage.maxAttempts}.", e)
            }
            result = JobFailed(e.message ?: "Failed to send queued message.")
        } finally {
            finish(queuedMessage, attempt, context)
        }

        return result
    }

    /** Moves the message PENDING -> PROCESSING under a state guard and records the attempt. */
    private fun claim(queuedMessage: QueuedMessage): DeliveryAttempt? {
        return db {
            val now = System.currentTimeMillis()
            val updated = QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessage.id) and (QueuedMessageTable.state eq QueuedMessageState.PENDING) }) { statement ->
                statement[QueuedMessageTable.state] = QueuedMessageState.PROCESSING
                statement[QueuedMessageTable.attemptCount] = queuedMessage.attemptCount + 1
                statement[QueuedMessageTable.lastStateChangeAt] = now
                statement[QueuedMessageTable.nextAttemptAt] = null
            }
            if (updated != 1) {
                return@db null
            }
            queuedMessage._state = QueuedMessageState.PROCESSING
            queuedMessage.attemptCount += 1
            queuedMessage.lastStateChangeAt = now
            queuedMessage.nextAttemptAt = null
            DeliveryAttemptTable.create(DeliveryAttempt(queuedMessage.id, DeliveryAttemptState.SENDING))
        }
    }

    /**
     * Records the outcome. Guarded on PROCESSING so a concurrent external transition (the queue
     * processor timing out a stalled message) is neither clobbered nor resurrected.
     */
    private fun finish(queuedMessage: QueuedMessage, attempt: DeliveryAttempt, context: JobContext) {
        val updated = db {
            QueuedMessageTable.update({ (QueuedMessageTable.id eq queuedMessage.id) and (QueuedMessageTable.state eq QueuedMessageState.PROCESSING) }) { statement ->
                statement[QueuedMessageTable.state] = queuedMessage.state
                statement[QueuedMessageTable.lastStateChangeAt] = queuedMessage.lastStateChangeAt
                statement[QueuedMessageTable.nextAttemptAt] = queuedMessage.nextAttemptAt
                statement[QueuedMessageTable.providerMessageId] = queuedMessage.providerMessageId
            }
        }
        if (updated != 1) {
            context.log.error("Queued message ${queuedMessage.id} was externally transitioned while sending; final state ${queuedMessage.state} was not recorded.")
        }
        attempt.endedAt = System.currentTimeMillis()
        db { DeliveryAttemptTable.update(attempt) }
    }

    private fun shouldTrackReceipt(queuedMessage: QueuedMessage, endpoint: Endpoint, result: SendResult): Boolean {
        return queuedMessage.trackReceipt && result.hasMessageId && endpoint.provider.supports(Capability.STATUS_LOOKUP)
    }
}
