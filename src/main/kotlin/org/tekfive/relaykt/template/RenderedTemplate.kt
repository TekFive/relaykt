package org.tekfive.relaykt.template

import org.tekfive.relaykt.Attachment
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.email.EmailMessage
import org.tekfive.relaykt.sms.SmsMessage
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessagePriority

/** Output of [TemplateRenderer.render]: rendered subject, both bodies, and collected sensitivity tags. */
data class RenderedTemplate(
    val subject: String,
    val htmlBody: String,
    val textBody: String,
    val sensitivityTags: Set<String> = emptySet(),
) {

    /** Builds an HTML email (falls back to the text body when the template has no HTML body). */
    fun toEmailMessage(
        to: List<MessageAddress>,
        from: MessageAddress,
        cc: List<MessageAddress> = emptyList(),
        bcc: List<MessageAddress> = emptyList(),
        replyTo: MessageAddress? = null,
        attachments: List<Attachment> = emptyList(),
    ): EmailMessage {
        val useHtml = htmlBody.isNotBlank()
        return EmailMessage(
            to = to,
            from = from,
            subject = subject.takeIf { it.isNotBlank() },
            body = if (useHtml) htmlBody else textBody,
            contentType = if (useHtml) EmailMessage.HTML_CONTENT_TYPE else EmailMessage.TEXT_CONTENT_TYPE,
            cc = cc,
            bcc = bcc,
            replyTo = replyTo,
            attachments = attachments,
        )
    }

    /** Builds an SMS from the text body. */
    fun toSmsMessage(to: List<MessageAddress>, from: MessageAddress? = null): SmsMessage =
        SmsMessage(to = to, body = textBody, from = from)

    /** Builds a team message from the subject and text body. */
    fun toTeamMessage(
        to: List<MessageAddress>,
        from: MessageAddress? = null,
        priority: TeamMessagePriority = TeamMessagePriority.NORMAL,
        attachments: List<Attachment> = emptyList(),
    ): TeamMessage = TeamMessage(to = to, body = textBody, subject = subject.takeIf { it.isNotBlank() }, from = from, priority = priority, attachments = attachments)
}
