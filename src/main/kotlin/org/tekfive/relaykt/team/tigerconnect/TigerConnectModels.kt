package org.tekfive.relaykt.team.tigerconnect

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

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
) : ToJsonObject

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
) {
    companion object : FromJsonObject<TigerConnectMessageStatusResponse>
}
