package org.tekfive.relaykt.support

import org.tekfive.relaykt.Attachment
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.email.EmailMessage
import org.tekfive.relaykt.sms.SmsMessage
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessagePriority

object TestMessages {

    val to = MessageAddress("to@example.com", "To Person")
    val cc = MessageAddress("cc@example.com", "Cc Person")
    val from = MessageAddress("noreply@example.com", "RelayKt")

    fun email(
        to: List<MessageAddress> = listOf(this.to),
        cc: List<MessageAddress> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        html: Boolean = false,
    ) = EmailMessage(
        to = to,
        from = from,
        subject = "Subject",
        body = if (html) "<p>Hello & goodbye</p>" else "Body",
        contentType = if (html) EmailMessage.HTML_CONTENT_TYPE else EmailMessage.TEXT_CONTENT_TYPE,
        cc = cc,
        attachments = attachments,
    )

    fun sms(to: List<MessageAddress> = listOf(MessageAddress("+15555550100")), from: MessageAddress? = MessageAddress("+15555550999")) =
        SmsMessage(to = to, body = "Ping", from = from)

    fun team(
        to: List<MessageAddress> = listOf(MessageAddress("#general")),
        priority: TeamMessagePriority = TeamMessagePriority.NORMAL,
        attachments: List<Attachment> = emptyList(),
    ) = TeamMessage(to = to, body = "Team body", subject = "Team subject", priority = priority, attachments = attachments)

    fun attachment(size: Int = 3) = Attachment("file.txt", "text/plain", ByteArray(size) { 'a'.code.toByte() })
}
