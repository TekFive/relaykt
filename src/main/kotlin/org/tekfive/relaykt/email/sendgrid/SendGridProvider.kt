package org.tekfive.relaykt.email.sendgrid

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Attachment
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.email.EmailMessage
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import java.util.Base64

/** Email through the Twilio SendGrid v3 API, with status lookup via the Email Activity API. */
object SendGridProvider : Provider<EmailMessage> {

    /** Replaceable for tests. */
    internal var clientFactory: (SendGridConfiguration) -> SendGridClient = { SendGridClient(it) }

    override val id: String = "sendgrid"

    override val channel: Channel = Channel.EMAIL

    override val capabilities: Set<Capability> = setOf(Capability.STATUS_LOOKUP, Capability.ATTACHMENTS, Capability.MULTIPLE_RECIPIENTS)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(SendGridConfiguration, configuration)
    }

    override fun send(message: EmailMessage, configuration: JsonObject): SendResult {
        val client = clientFactory(ProviderConfigurations.parse(SendGridConfiguration, configuration))
        val response = client.sendMail(buildSendRequest(message))
        return SendResult(
            messageId = response.messageId.orEmpty(),
            providerId = id,
            status = mapSendStatus(response.status),
        )
    }

    override fun status(messageId: String, configuration: JsonObject): DeliveryStatus? {
        val client = clientFactory(ProviderConfigurations.parse(SendGridConfiguration, configuration))
        val response = client.getEmailActivity(messageId) ?: return null
        return mapActivity(response)
    }

    internal fun mapActivity(response: SendGridEmailActivityResponse): DeliveryStatus {
        val fromEvents = response.events.asReversed().firstNotNullOfOrNull { event ->
            mapEventName(event.eventName) ?: mapEventName(event.status)
        }
        return fromEvents ?: mapEventName(response.status) ?: DeliveryStatus.UNKNOWN
    }

    private fun mapSendStatus(status: String?): DeliveryStatus = when (status?.lowercase()) {
        "sent", "processed" -> DeliveryStatus.SENT
        else -> DeliveryStatus.QUEUED
    }

    private fun mapEventName(name: String?): DeliveryStatus? = when (name?.lowercase()) {
        "open", "opened", "click", "clicked" -> DeliveryStatus.OPENED
        "delivered" -> DeliveryStatus.DELIVERED
        "processed", "sent" -> DeliveryStatus.SENT
        "queued", "accepted" -> DeliveryStatus.QUEUED
        "bounce", "bounced", "dropped", "deferred", "failed", "blocked" -> DeliveryStatus.FAILED
        else -> null
    }

    internal fun buildSendRequest(message: EmailMessage): SendGridMailSendRequest {
        return SendGridMailSendRequest(
            personalizations = listOf(
                SendGridPersonalization(
                    to = message.to.map(::toEmailAddress),
                    cc = message.cc.map(::toEmailAddress),
                    bcc = message.bcc.map(::toEmailAddress),
                ),
            ),
            from = toEmailAddress(message.from),
            subject = message.subject,
            content = listOf(SendGridContent(type = if (message.isHtml) EmailMessage.HTML_CONTENT_TYPE else EmailMessage.TEXT_CONTENT_TYPE, value = message.body)),
            replyTo = message.replyTo?.let(::toEmailAddress),
            attachments = message.attachments.map(::toAttachment),
        )
    }

    private fun toAttachment(attachment: Attachment) = SendGridAttachment(
        content = Base64.getEncoder().encodeToString(attachment.content),
        type = attachment.contentType,
        filename = attachment.fileName,
    )

    private fun toEmailAddress(address: MessageAddress) = SendGridEmailAddress(email = address.address, name = address.displayName)
}
