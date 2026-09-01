package org.tekfive.relaykt.email.smtp

import jakarta.activation.DataHandler
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.tekfive.ack.Ack
import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Attachment
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.email.EmailMessage
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import org.tekfive.relaykt.tls.TlsCertificatePins
import java.util.Properties
import jakarta.mail.MessagingException as JakartaMessagingException

/**
 * Sends email through any SMTP server using Jakarta Mail. SMTP offers no message ids or delivery
 * status, so [status] is unsupported.
 */
object SmtpProvider : Provider<EmailMessage> {

    val connectionTimeoutDefaultMSecsAck = Ack.int("CONNECTION_TIMEOUT_DEFAULT_MSECS", 10_000, min = 1, namespace = NAMESPACE, description = "Default SMTP connection timeout in milliseconds.")

    val timeoutDefaultMSecsAck = Ack.int("TIMEOUT_DEFAULT_MSECS", 10_000, min = 1, namespace = NAMESPACE, description = "Default SMTP socket read timeout in milliseconds.")

    val writeTimeoutDefaultMSecsAck = Ack.int("WRITE_TIMEOUT_DEFAULT_MSECS", 10_000, min = 1, namespace = NAMESPACE, description = "Default SMTP socket write timeout in milliseconds.")

    override val id: String = "smtp"

    override val channel: Channel = Channel.EMAIL

    override val capabilities: Set<Capability> = setOf(Capability.ATTACHMENTS, Capability.MULTIPLE_RECIPIENTS)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(SmtpConfiguration, configuration)
    }

    override fun send(message: EmailMessage, configuration: JsonObject): SendResult {
        val smtpConfiguration = ProviderConfigurations.parse(SmtpConfiguration, configuration)
        val session = Session.getInstance(buildSessionProperties(smtpConfiguration), buildAuthenticator(smtpConfiguration))
        val mimeMessage = buildMimeMessage(message, session)

        try {
            Transport.send(mimeMessage)
        } catch (e: SendFailedException) {
            val invalidCount = e.invalidAddresses?.size ?: 0
            val unsentCount = e.validUnsentAddresses?.size ?: 0
            // SendFailedException messages enumerate recipient addresses, so neither the original
            // message nor the cause chain may be propagated — counts only.
            throw RelayException("SMTP send failed: $invalidCount invalid / $unsentCount unsent recipient(s)", recoverable = false)
        } catch (e: JakartaMessagingException) {
            // Connection-level failure messages carry host/port only, so chaining the cause is safe.
            throw RelayException("SMTP connection or protocol failure (${e.javaClass.simpleName})", recoverable = true, cause = e)
        }

        return SendResult(messageId = mimeMessage.messageID.orEmpty(), providerId = id, status = DeliveryStatus.SENT)
    }

    internal fun buildMimeMessage(message: EmailMessage, session: Session): MimeMessage {
        requireNoLineBreaks(message.contentType, "content type")

        val mimeMessage = MimeMessage(session)
        mimeMessage.setFrom(toInternetAddress(message.from))
        mimeMessage.setRecipients(Message.RecipientType.TO, message.to.map(::toInternetAddress).toTypedArray())
        if (message.cc.isNotEmpty()) {
            mimeMessage.setRecipients(Message.RecipientType.CC, message.cc.map(::toInternetAddress).toTypedArray())
        }
        if (message.bcc.isNotEmpty()) {
            mimeMessage.setRecipients(Message.RecipientType.BCC, message.bcc.map(::toInternetAddress).toTypedArray())
        }
        message.replyTo?.let { mimeMessage.replyTo = arrayOf(toInternetAddress(it)) }

        if (!message.subject.isNullOrBlank()) {
            mimeMessage.setSubject(stripLineBreaks(message.subject), Charsets.UTF_8.name())
        }

        if (message.attachments.isEmpty()) {
            mimeMessage.setContent(message.body, "${message.contentType}; charset=UTF-8")
        } else {
            val multipart = MimeMultipart("mixed")
            val bodyPart = MimeBodyPart()
            bodyPart.setContent(message.body, "${message.contentType}; charset=UTF-8")
            multipart.addBodyPart(bodyPart)
            for (attachment in message.attachments) {
                multipart.addBodyPart(buildAttachmentPart(attachment))
            }
            mimeMessage.setContent(multipart)
        }

        mimeMessage.saveChanges()
        return mimeMessage
    }

    private fun buildAttachmentPart(attachment: Attachment): MimeBodyPart {
        requireNoLineBreaks(attachment.contentType, "attachment content type")
        requireNoLineBreaks(attachment.fileName, "attachment file name")

        val part = MimeBodyPart()
        part.dataHandler = DataHandler(ByteArrayDataSource(attachment.content, attachment.contentType))
        part.fileName = attachment.fileName
        part.disposition = MimeBodyPart.ATTACHMENT
        return part
    }

    internal fun buildSessionProperties(configuration: SmtpConfiguration): Properties {
        val startTls = configuration.startTls ?: true
        return Properties().apply {
            put("mail.smtp.host", configuration.host)
            put("mail.smtp.port", (configuration.port ?: 587).toString())
            put("mail.smtp.auth", configuration.shouldAuthenticate.toString())
            put("mail.smtp.starttls.enable", startTls.toString())
            // When STARTTLS is enabled, refuse to fall back to plaintext if the server rejects it.
            put("mail.smtp.starttls.required", startTls.toString())
            put("mail.smtp.ssl.enable", (configuration.sslEnabled ?: false).toString())
            // Jakarta Mail defaults server identity checking to false; always enable it.
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", (configuration.connectionTimeoutMSecs ?: connectionTimeoutDefaultMSecsAck()).toString())
            put("mail.smtp.timeout", (configuration.timeoutMSecs ?: timeoutDefaultMSecsAck()).toString())
            put("mail.smtp.writetimeout", (configuration.writeTimeoutMSecs ?: writeTimeoutDefaultMSecsAck()).toString())
            if (configuration.tls.certificatePinningEnabled) {
                put("mail.smtp.ssl.socketFactory", TlsCertificatePins.smtpSocketFactory(configuration.tls.certificatePins))
                put("mail.smtp.ssl.socketFactory.fallback", "false")
            }
        }
    }

    internal fun buildAuthenticator(configuration: SmtpConfiguration): Authenticator? {
        if (!configuration.shouldAuthenticate) {
            return null
        }
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(configuration.username, configuration.password)
            }
        }
    }

    private fun toInternetAddress(address: MessageAddress): InternetAddress {
        requireNoLineBreaks(address.address, "address")
        // The multi-arg InternetAddress constructor does not validate, so validate() is explicit.
        // AddressException messages include the raw address, so a scrubbed exception replaces it.
        try {
            val internetAddress = if (address.displayName.isNullOrBlank()) {
                InternetAddress(address.address)
            } else {
                InternetAddress(address.address, stripLineBreaks(address.displayName), Charsets.UTF_8.name())
            }
            internetAddress.validate()
            return internetAddress
        } catch (e: AddressException) {
            throw IllegalArgumentException("Invalid email address")
        }
    }

    private fun requireNoLineBreaks(value: String, description: String) {
        if (value.contains('\r') || value.contains('\n')) {
            throw IllegalArgumentException("Email $description contains illegal line break characters")
        }
    }

    private fun stripLineBreaks(value: String): String = value.replace("\r", "").replace("\n", "")

    const val NAMESPACE = "SMTP"
}
