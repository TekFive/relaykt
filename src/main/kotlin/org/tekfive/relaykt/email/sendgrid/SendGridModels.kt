package org.tekfive.relaykt.email.sendgrid

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

data class SendGridMailSendRequest(
    val personalizations: List<SendGridPersonalization>,
    val from: SendGridEmailAddress,
    val subject: String? = null,
    val content: List<SendGridContent>,
    val replyTo: SendGridEmailAddress? = null,
    val attachments: List<SendGridAttachment> = emptyList(),
) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json {
        "personalizations" set personalizations
        "from" set from
        if (subject != null) "subject" set subject
        "content" set content
        if (replyTo != null) "reply_to" set replyTo
        if (attachments.isNotEmpty()) "attachments" set attachments
    }
}

data class SendGridPersonalization(
    val to: List<SendGridEmailAddress>,
    val cc: List<SendGridEmailAddress> = emptyList(),
    val bcc: List<SendGridEmailAddress> = emptyList(),
) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json {
        "to" set to
        if (cc.isNotEmpty()) "cc" set cc
        if (bcc.isNotEmpty()) "bcc" set bcc
    }
}

data class SendGridEmailAddress(val email: String, val name: String? = null) : ToJsonObject {
    override fun toJsonObject(): JsonObject = json {
        "email" set email
        if (name != null) "name" set name
    }
}

data class SendGridContent(val type: String, val value: String) : ToJsonObject

data class SendGridAttachment(
    val content: String,
    val type: String,
    val filename: String,
    val disposition: String = "attachment",
) : ToJsonObject

data class SendGridMailSendResponse(val messageId: String?, val status: String?)

data class SendGridEmailEvent(val eventName: String?, val status: String?, val timestamp: String?)

data class SendGridEmailActivityResponse(
    val messageId: String? = null,
    val status: String? = null,
    val events: List<SendGridEmailEvent> = emptyList(),
)
