package org.tekfive.relaykt.team.tigerconnect

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

/** Generic named record returned by the user/group/role/distribution-list lookup APIs. */
data class TigerConnectRecord(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
) {
    companion object : FromJsonObject<TigerConnectRecord>
}

data class TigerConnectSendRequest(
    val targetType: String,
    val targetId: String,
    val body: String,
    val subject: String? = null,
    val priority: String? = null,
) : ToJsonObject {
    /** targetType is local routing information, not a TigerConnect wire field. */
    override fun toJsonObject(): JsonObject = json {
        "recipient" set targetId
        // TigerConnect has no subject field; retain it as a heading in the message body.
        "body" set if (subject.isNullOrBlank()) body else "$subject\n\n$body"
        if (priority != null) {
            "priority" set when (priority.lowercase()) {
                "normal" -> 0
                "high", "urgent" -> 1
                else -> throw IllegalArgumentException("Unsupported TigerConnect priority")
            }
        }
    }
}

data class TigerConnectSendResponse(
    val messageId: String? = null,
    val id: String? = null,
    val status: String? = null,
) {
    val resolvedMessageId: String?
        get() = messageId ?: id

    companion object : FromJsonObject<TigerConnectSendResponse>
}

data class TigerConnectMessageStatusResponse(
    val messageId: String? = null,
    val status: String? = null,
    val recipientStatuses: List<String?> = emptyList(),
    val isRecalled: Boolean = false,
) {
    companion object : FromJsonObject<TigerConnectMessageStatusResponse>
}
