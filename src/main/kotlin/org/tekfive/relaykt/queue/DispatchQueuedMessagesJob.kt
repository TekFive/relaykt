package org.tekfive.relaykt.queue

import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobCompleted
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobResult
import org.tekfive.keep.job.schedule.FixedIntervalJobSpec

/** Keep owns polling, failure reporting, and shutdown for the durable message queue. */
class DispatchQueuedMessagesJob : Job {
    override fun execute(context: JobContext): JobResult {
        var changes = 0
        do {
            context.checkIn()
            // Drain successive bounded batches without waiting for another scheduled run.
            val processed = MessageQueueProcessor.processOnce(checkIn = { context.checkIn() })
            changes += processed
        } while (processed > 0)

        return JobCompleted("Queue sweep completed; $changes message state change(s).")
    }

    companion object : FixedIntervalJobSpec {
        override val defaultInternalSeconds = 20L
        override val runImmediatelyOnFirstSchedule = true
        override val exclusiveExecution = true
        override val estimateRuntime = false

        override fun createJob(): Job = DispatchQueuedMessagesJob()
    }
}
