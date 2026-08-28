package org.tekfive.relaykt.email

import org.tekfive.jfk.FromJsonObject
import org.tekfive.relaykt.Attachment
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.MessageAddress

/**
 * An email. [body] is interpreted according to [contentType]; use [html] / [text] to build one.
 */
class EmailMessage(
    override val to: List<MessageAddress>,
    override val from: MessageAddress,
    val subject: String?,
    override val body: String,
    val contentType: String = TEXT_CONTENT_TYPE,
    val cc: List<MessageAddress> = emptyList(),
    val bcc: List<MessageAddress> = emptyList(),
    val replyTo: MessageAddress? = null,
    override val attachments: List<Attachment> = emptyList(),
) : Message() {

    override val channel: Channel = Channel.EMAIL

    override val allRecipients: List<MessageAddress>
        get() = to + cc + bcc

    /** Anything starting with `text/html` (e.g. `text/html; charset=UTF-8`) is HTML. */
    val isHtml: Boolean
        get() = contentType.trim().startsWith(HTML_CONTENT_TYPE, ignoreCase = true)

    init {
        requireRecipients()
        require(contentType.isNotBlank()) { "Email content type must not be blank" }
    }

    companion object : FromJsonObject<EmailMessage> {
        const val TEXT_CONTENT_TYPE = "text/plain"
        const val HTML_CONTENT_TYPE = "text/html"

        fun text(to: List<MessageAddress>, from: MessageAddress, subject: String?, body: String, attachments: List<Attachment> = emptyList()): EmailMessage =
            EmailMessage(to = to, from = from, subject = subject, body = body, contentType = TEXT_CONTENT_TYPE, attachments = attachments)

        fun html(to: List<MessageAddress>, from: MessageAddress, subject: String?, body: String, attachments: List<Attachment> = emptyList()): EmailMessage =
            EmailMessage(to = to, from = from, subject = subject, body = body, contentType = HTML_CONTENT_TYPE, attachments = attachments)
    }
}
