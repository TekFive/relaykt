package org.tekfive.relaykt.queue

import org.tekfive.keep.data.Data
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType
import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.TrackUpdatedAt
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.dataEnum
import org.tekfive.keep.data.fkey
import org.tekfive.keep.data.timestamp
import org.tekfive.keep.db.dbTransactionAt
import org.tekfive.keep.encryption.encryptedText
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.relaykt.DeliveryStatus

enum class DeliveryReceiptStatus(
    override val id: Int,
    override val displayName: String,
    val description: String,
) : DataEnum {
    WAITING(1, "Waiting", "The message has been sent and is waiting for a delivery update from the provider."),
    DELIVERY_FAILURE(2, "Delivery Failure", "The provider reported that delivery failed."),
    RECEIVED(3, "Received", "The provider confirmed that the message reached the recipient."),
    TIMED_OUT(4, "Timed Out", "Tracking did not complete before the timeout expired."),
    ;

    val completed: Boolean
        get() = this != WAITING

    companion object : DataEnumColumnType<DeliveryReceiptStatus>()
}

/**
 * Per-recipient delivery tracking for a queued message sent with [QueueOptions.trackReceipt].
 * [UpdateDeliveryReceiptsJob] polls the provider and moves receipts out of [DeliveryReceiptStatus.WAITING].
 */
class DeliveryReceipt(
    val queuedMessageId: Long,
    val endpointId: String,
    val providerId: String,
    val providerMessageId: String,
    val recipientAddress: String,
    var status: DeliveryReceiptStatus = DeliveryReceiptStatus.WAITING,
    var lastDeliveryStatus: DeliveryStatus? = null,
    var lastCheckedAt: Long? = null,
    val createdAt: Long = dbTransactionAt(),
    override var updatedAt: Long = createdAt,
) : Data(), TrackUpdatedAt {

    /** Provider tracking data as JSON (ids and last known status only — never message content). */
    val details: JsonObject
        get() = json {
            "providerId" set providerId
            "providerMessageId" set providerMessageId
            "lastDeliveryStatus" setEnum lastDeliveryStatus
            "lastCheckedAt" set lastCheckedAt
        }
}

object DeliveryReceiptTable : DataTable<DeliveryReceipt>("relay_delivery_receipts") {
    val queuedMessageId = fkey("queued_message_id", QueuedMessageTable)
    val endpointId = varchar("endpoint_id", 100)
    val providerId = varchar("provider_id", 50)
    val providerMessageId = varchar("provider_message_id", 500)
    val recipientAddress = encryptedText("recipient_address")
    val status = dataEnum<DeliveryReceiptStatus>("status_id").index()
    val lastDeliveryStatus = enumerationByName<DeliveryStatus>("last_delivery_status", 20).nullable()
    val lastCheckedAt = timestamp("last_checked_at").nullable()
    val createdAt = createdAt()
    val updatedAt = timestamp("updated_at")
}
