package org.tekfive.relaykt.email.zeptomail

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

/** Email through Zoho ZeptoMail; status lookup needs an OAuth token on the endpoint. */
object ZeptoMailProvider : Provider<EmailMessage> {

    internal var clientFactory: (ZeptoMailConfiguration) -> ZeptoMailClient = { ZeptoMailClient(it) }

    override val id: String = "zeptomail"

    override val channel: Channel = Channel.EMAIL

    override val capabilities: Set<Capability> = setOf(Capability.STATUS_LOOKUP, Capability.ATTACHMENTS, Capability.MULTIPLE_RECIPIENTS)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(ZeptoMailConfiguration, configuration)
    }

    override fun send(message: EmailMessage, configuration: JsonObject): SendResult {
        val zeptoConfiguration = ProviderConfigurations.parse(ZeptoMailConfiguration, configuration)
        val response = clientFactory(zeptoConfiguration).sendMail(buildSendRequest(message, zeptoConfiguration))
        return SendResult(
            messageId = response.requestId.orEmpty(),
            providerId = id,
            status = if (response.status?.trim()?.lowercase() == "processed") DeliveryStatus.SENT else DeliveryStatus.QUEUED,
        )
    }

    override fun status(messageId: String, configuration: JsonObject): DeliveryStatus? {
        val zeptoConfiguration = ProviderConfigurations.parse(ZeptoMailConfiguration, configuration)
        if (zeptoConfiguration.oauthAuthorizationHeader == null) {
            return null
        }
        val response = clientFactory(zeptoConfiguration).getEmailStatus(messageId) ?: return null
        return mapStatus(response)
    }

    internal fun mapStatus(response: ZeptoMailEmailStatusResponse): DeliveryStatus {
        if (response.openCount > 0) {
            return DeliveryStatus.OPENED
        }
        return when {
            response.hasFailure && response.hasDeliveredRecipients -> DeliveryStatus.UNKNOWN
            response.hasFailure -> DeliveryStatus.FAILED
            response.hasDeliveredRecipients -> DeliveryStatus.DELIVERED
            else -> mapProviderStatus(response.status)
        }
    }

    private fun mapProviderStatus(status: String?): DeliveryStatus = when (status?.trim()?.lowercase()) {
        "queued" -> DeliveryStatus.QUEUED
        "processed" -> DeliveryStatus.SENT
        "delivered" -> DeliveryStatus.DELIVERED
        "hard bounce", "soft bounce", "mail failure", "process failed", "failed" -> DeliveryStatus.FAILED
        else -> DeliveryStatus.UNKNOWN
    }

    internal fun buildSendRequest(message: EmailMessage, configuration: ZeptoMailConfiguration): ZeptoMailSendRequest {
        return ZeptoMailSendRequest(
            from = toEmailAddress(message.from),
            to = message.to.map(::toRecipient),
            cc = message.cc.map(::toRecipient),
            bcc = message.bcc.map(::toRecipient),
            replyTo = listOfNotNull(message.replyTo?.let(::toEmailAddress)),
            subject = message.subject,
            textBody = if (message.isHtml) null else message.body,
            htmlBody = if (message.isHtml) message.body else null,
            trackOpens = configuration.trackOpens,
            trackClicks = configuration.trackClicks,
            bounceAddress = configuration.bounceAddress?.trim()?.takeIf { it.isNotBlank() },
            attachments = message.attachments.map(::toAttachment),
        )
    }

    private fun toAttachment(attachment: Attachment) = ZeptoMailAttachment(
        content = Base64.getEncoder().encodeToString(attachment.content),
        mimeType = attachment.contentType,
        name = attachment.fileName,
    )

    private fun toRecipient(address: MessageAddress) = ZeptoMailRecipient(toEmailAddress(address))

    private fun toEmailAddress(address: MessageAddress) = ZeptoMailEmailAddress(address = address.address, name = address.displayName)
}
