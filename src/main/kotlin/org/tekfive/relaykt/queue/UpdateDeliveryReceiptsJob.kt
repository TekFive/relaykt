package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.eq
import org.tekfive.ack.Ack
import org.tekfive.ack.ackNamespace
import org.tekfive.keep.db.db
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.Relay
import org.tekfive.relaykt.endpoint.Endpoint

/**
 * Scheduled job that polls providers for the status of WAITING [DeliveryReceipt]s and resolves them
 * to RECEIVED, DELIVERY_FAILURE, or TIMED_OUT.
 */
class UpdateDeliveryReceiptsJob : Job {

    companion object : FixedIntervalJobSpec {

        override val estimateRuntime: Boolean = false

        override val exclusiveExecution: Boolean = true

        override val defaultInternalSeconds: Long = 5L * 60

        override val intervalSecondsProperty: Ack<Long>
            get() = Ack.long("FIXED_INTERVAL_SECONDS", defaultInternalSeconds, min = 1L, namespace = ackNamespace(getNamespaceClass()), description = "Interval in seconds between delivery receipt polls.")

        val defaultMaxReceiptWaitMinutesAck = Ack.int("DEFAULT_MAX_RECEIPT_WAIT_MINUTES", 24 * 60, min = 1, namespace = MessageQueueProcessor.NAMESPACE, description = "Default minutes a delivery receipt may wait for provider confirmation before timing out.")

        override fun createJob(): Job = UpdateDeliveryReceiptsJob()

        internal fun mapReceiptStatus(status: DeliveryStatus?): DeliveryReceiptStatus? = when {
            status == null -> null
            status.isReceived -> DeliveryReceiptStatus.RECEIVED
            status == DeliveryStatus.FAILED -> DeliveryReceiptStatus.DELIVERY_FAILURE
            else -> null
        }
    }

    override fun execute(context: JobContext): JobResult {
        val now = System.currentTimeMillis()
        val waiting = db { DeliveryReceiptTable.findWhere(DeliveryReceiptTable.status eq DeliveryReceiptStatus.WAITING) }
        if (waiting.isEmpty()) {
            return JobCompleted()
        }

        val waitMinutesByMessage = mutableMapOf<Long, Int>()
        val endpointCache = mutableMapOf<String, Endpoint?>()
        var updated = 0

        // Group by provider message id: one status call covers every recipient of that send.
        for ((_, receipts) in waiting.groupBy { it.providerMessageId to it.endpointId }) {
            val first = receipts.first()
            val endpoint = endpointCache.getOrPut(first.endpointId) { Relay.findEndpoint(first.endpointId) }

            val deliveryStatus = if (endpoint == null) {
                context.log.warn("Endpoint ${first.endpointId} could not be resolved while updating delivery receipts.")
                null
            } else {
                try {
                    Relay.status(first.providerMessageId, endpoint)
                } catch (e: Exception) {
                    context.log.warn("Status lookup failed for endpoint ${first.endpointId}.", e)
                    null
                }
            }

            for (receipt in receipts) {
                val maxWaitMinutes = waitMinutesByMessage.getOrPut(receipt.queuedMessageId) {
                    db { QueuedMessageTable.findById(receipt.queuedMessageId)?.maxReceiptWaitMinutes } ?: defaultMaxReceiptWaitMinutesAck()
                }
                val newStatus = mapReceiptStatus(deliveryStatus)
                    ?: if (now - receipt.createdAt >= maxWaitMinutes * 60_000L) DeliveryReceiptStatus.TIMED_OUT else null

                receipt.lastCheckedAt = now
                deliveryStatus?.let { receipt.lastDeliveryStatus = it }
                if (newStatus != null) {
                    receipt.status = newStatus
                    updated++
                }
                db { DeliveryReceiptTable.update(receipt) }
            }
        }

        return JobCompleted("Checked ${waiting.size} waiting receipts; $updated resolved.")
    }
}
