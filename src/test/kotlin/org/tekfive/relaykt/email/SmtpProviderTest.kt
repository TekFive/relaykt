package org.tekfive.relaykt.email

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.MimeMultipart
import org.tekfive.jfk.json
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.email.smtp.SmtpConfiguration
import org.tekfive.relaykt.email.smtp.SmtpProvider
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.tls.TlsCertificatePinsTest
import org.tekfive.relaykt.tls.TlsConfiguration
import java.util.Properties
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SmtpProviderTest {

    @Test
    fun `configuration parses from endpoint json with defaults`() {
        val configuration = SmtpConfiguration.fromJson(json { "host" set "smtp.example.com" })
        val properties = SmtpProvider.buildSessionProperties(configuration)

        assertEquals("smtp.example.com", properties["mail.smtp.host"])
        assertEquals("587", properties["mail.smtp.port"])
        assertEquals("true", properties["mail.smtp.starttls.required"])
        assertEquals("true", properties["mail.smtp.ssl.checkserveridentity"])
        assertEquals("false", properties["mail.smtp.auth"])
        assertNull(SmtpProvider.buildAuthenticator(configuration))
        assertFailsWith<IllegalArgumentException> { SmtpProvider.validateConfiguration(json { "host" set "" }) }
    }

    @Test
    fun `authentication is enabled only with credentials`() {
        val configuration = SmtpConfiguration("smtp.example.com", username = "user", password = "secret")
        assertEquals("true", SmtpProvider.buildSessionProperties(configuration)["mail.smtp.auth"])
        assertNotNull(SmtpProvider.buildAuthenticator(configuration))
        assertEquals(false, configuration.toString().contains("secret"))
    }

    @Test
    fun `certificate pins configure a non-fallback TLS socket factory`() {
        val configuration = SmtpConfiguration(
            host = "smtp.example.com",
            tls = TlsConfiguration.pinned(TlsCertificatePinsTest.TEST_PIN),
        )

        val properties = SmtpProvider.buildSessionProperties(configuration)

        assertNotNull(properties["mail.smtp.ssl.socketFactory"] as? SSLSocketFactory)
        assertEquals("false", properties["mail.smtp.ssl.socketFactory.fallback"])
        assertFailsWith<IllegalArgumentException> {
            SmtpConfiguration(
                host = "smtp.example.com",
                startTls = false,
                sslEnabled = false,
                tls = TlsConfiguration.pinned(TlsCertificatePinsTest.TEST_PIN),
            )
        }
    }

    @Test
    fun `mime message carries recipients, subject, body, and attachments`() {
        val session = Session.getInstance(Properties())
        val message = TestMessages.email(cc = listOf(TestMessages.cc), attachments = listOf(TestMessages.attachment()))

        val mime = SmtpProvider.buildMimeMessage(message.copyWith(replyTo = MessageAddress("reply@example.com")), session)

        assertEquals("Subject", mime.subject)
        assertEquals(1, mime.getRecipients(Message.RecipientType.TO).size)
        assertEquals(1, mime.getRecipients(Message.RecipientType.CC).size)
        assertEquals("reply@example.com", mime.replyTo.single().toString())
        val multipart = mime.content as MimeMultipart
        assertEquals(2, multipart.count)
        assertEquals("file.txt", multipart.getBodyPart(1).fileName)
    }

    @Test
    fun `header injection attempts are rejected or stripped`() {
        val session = Session.getInstance(Properties())
        assertFailsWith<IllegalArgumentException> {
            SmtpProvider.buildMimeMessage(TestMessages.email().copyWith(contentType = "text/plain\r\nBcc: x@y.z"), session)
        }
        val mime = SmtpProvider.buildMimeMessage(TestMessages.email().copyWith(subject = "Hi\r\nBcc: x@y.z"), session)
        assertEquals("HiBcc: x@y.z", mime.subject)
    }

    private fun EmailMessage.copyWith(subject: String? = this.subject, contentType: String = this.contentType, replyTo: MessageAddress? = this.replyTo) =
        EmailMessage(to, from, subject, body, contentType, cc, bcc, replyTo, attachments)
}
