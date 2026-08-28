package org.tekfive.relaykt.support

import org.tekfive.jfk.JsonObject
import org.tekfive.keep.job.Job
import org.tekfive.keep.job.JobContext
import org.tekfive.keep.job.JobLogger

/** Minimal [JobContext] for executing KEEP jobs directly in tests. */
class FakeJobContext(job: Job, override val details: JsonObject?) : JobContext {
    override val jobId: Long = 1
    override val startedAt: Long = System.currentTimeMillis()
    override val type: String = job.javaClass.simpleName
    override val createdAt: Long = startedAt
    override val attempt: Int = 1
    override val maxRetries: Int = 0
    override val estimatedRuntimeSeconds: Int? = null
    override val log: JobLogger = JobLogger(job, this, null)

    override fun checkIn(now: Long) {}

    override fun updateDetails(details: JsonObject) {}
}
