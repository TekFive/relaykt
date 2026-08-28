package org.tekfive.relaykt.email.zeptomail

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

data class ZeptoMailSendRequest(
    val from: ZeptoMailEmailAddress,
    val to: List<ZeptoMailRecipient>,
    val cc: List<ZeptoMailRecipient> = emptyList(),
    val bcc: List<ZeptoMailRecipient> = emptyList(),
    val replyTo: List<ZeptoMailEmailAddress> = emptyList(),
    val subject: String? = null,
    val textBody: String? = null,
    val htmlBody: String? = null,
    val trackOpens: Boolean? = null,
    val trackClicks: Boolean? = null,
    val bounceAddress: String? = null,
    val attachments: List<ZeptoMailAttachment> = emptyList(),
) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json {
        "from" set from
        "to" set to
        if (cc.isNotEmpty()) "cc" set cc
        if (bcc.isNotEmpty()) "bcc" set bcc
        if (replyTo.isNotEmpty()) "reply_to" set replyTo
        if (subject != null) "subject" set subject
        if (textBody != null) "textbody" set textBody
        if (htmlBody != null) "htmlbody" set htmlBody
        if (trackOpens != null) "track_opens" set trackOpens
        if (trackClicks != null) "track_clicks" set trackClicks
        if (bounceAddress != null) "bounce_address" set bounceAddress
        if (attachments.isNotEmpty()) "attachments" set attachments
    }
}

data class ZeptoMailEmailAddress(val address: String, val name: String? = null) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json {
        "address" set address
        if (name != null) "name" set name
    }
}

data class ZeptoMailRecipient(val emailAddress: ZeptoMailEmailAddress) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json { "email_address" set emailAddress }
}

data class ZeptoMailAttachment(val content: String, val mimeType: String, val name: String) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json {
        "content" set content
        "mime_type" set mimeType
        "name" set name
    }
}

data class ZeptoMailSendResponse(val requestId: String? = null, val status: String? = null)

data class ZeptoMailEmailStatusResponse(
    val requestId: String? = null,
    val emailReference: String? = null,
    val status: String? = null,
    val openCount: Int = 0,
    val hasDeliveredRecipients: Boolean = false,
    val hasHardBounceRecipients: Boolean = false,
    val hasSoftBounceRecipients: Boolean = false,
    val hasMailFailureRecipients: Boolean = false,
    val hasProcessFailedRecipients: Boolean = false,
) {
    val hasFailure: Boolean
        get() = hasHardBounceRecipients || hasSoftBounceRecipients || hasMailFailureRecipients || hasProcessFailedRecipients
}
