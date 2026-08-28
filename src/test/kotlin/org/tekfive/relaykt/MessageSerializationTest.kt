package org.tekfive.relaykt

import org.tekfive.relaykt.email.EmailMessage
import org.tekfive.relaykt.sms.SmsMessage
import org.tekfive.relaykt.support.TestMessages
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessagePriority
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class MessageSerializationTest {

    @Test
    fun `email round-trips through json including attachments and cc`() {
        val original = TestMessages.email(cc = listOf(TestMessages.cc), attachments = listOf(TestMessages.attachment(5)), html = true)

        val restored = Channel.EMAIL.readMessage(original.toJsonObject())

        assertIs<EmailMessage>(restored)
        assertEquals(original.to, restored.to)
        assertEquals(original.cc, restored.cc)
        assertEquals(original.from, restored.from)
        assertEquals(original.subject, restored.subject)
        assertEquals(original.body, restored.body)
        assertEquals(EmailMessage.HTML_CONTENT_TYPE, restored.contentType)
        assertEquals(1, restored.attachments.size)
        assertEquals("file.txt", restored.attachments.single().fileName)
        assertContentEquals(original.attachments.single().content, restored.attachments.single().content)
        assertEquals(2, restored.allRecipients.size)
    }

    @Test
    fun `sms round-trips and keeps optional from`() {
        val restored = Channel.SMS.readMessage(TestMessages.sms(from = null).toJsonObject())
        assertIs<SmsMessage>(restored)
        assertNull(restored.from)
        assertEquals("Ping", restored.body)
    }

    @Test
    fun `team message round-trips priority`() {
        val restored = Channel.TEAM.readMessage(TestMessages.team(priority = TeamMessagePriority.URGENT).toJsonObject())
        assertIs<TeamMessage>(restored)
        assertEquals(TeamMessagePriority.URGENT, restored.priority)
        assertEquals("Team subject", restored.subject)
    }

    @Test
    fun `messages require recipients and addresses reject blanks`() {
        assertFailsWith<IllegalArgumentException> { TestMessages.email(to = emptyList()) }
        assertFailsWith<IllegalArgumentException> { MessageAddress(" ") }
        assertEquals(MessageAddress("a@b.c", "Ann"), MessageAddress(" a@b.c ", " Ann "))
        assertNull(MessageAddress("a@b.c", "  ").displayName)
        assertEquals("a@b.c", Channel.SMS.readMessage(TestMessages.sms(to = listOf(MessageAddress(" a@b.c "))).toJsonObject()).to.single().address)
        assertEquals("MessageAddress(<redacted>)", MessageAddress("a@b.c").toString())
    }
}
