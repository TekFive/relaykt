package org.tekfive.relaykt.queue

import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.description
import org.tekfive.keep.data.timestamp
import org.tekfive.keep.encryption.encryptedJsonObject
import org.tekfive.keep.encryption.encryptedStringList

object QueuedMessageTable : DataTable<QueuedMessage>("relay_queued_messages") {
    val channel = dataEnum<Channel>("channel_id")
    val endpointId = varchar("endpoint_id", 100).index()
    // Recipients and message content carry PHI/PII and are encrypted at rest.
    val recipients = encryptedStringList("recipients")
    val payload = encryptedJsonObject("payload")
    val label = varchar("label", 100).index()
    val description = description()
    val trackReceipt = bool("track_receipt")
    val deliverAfter = timestamp("deliver_after").nullable()
    val maxAttempts = integer("max_attempts")
    val maxReceiptWaitMinutes = integer("max_receipt_wait_minutes").nullable()
    val createdAt = createdAt()
    val state = dataEnum<QueuedMessageState>("state_id").index()
    val lastStateChangeAt = timestamp("last_state_change_at")
    val nextAttemptAt = timestamp("next_attempt_at").nullable()
    val attemptCount = integer("attempt_count")
    val providerMessageId = varchar("provider_message_id", 500).nullable()
}

private typealias Channel = org.tekfive.relaykt.Channel
