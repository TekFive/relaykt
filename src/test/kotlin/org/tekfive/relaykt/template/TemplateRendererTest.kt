package org.tekfive.relaykt.template

import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.email.EmailMessage
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateRendererTest {

    private fun template(
        subject: String = "Hello {{name}}",
        html: String = "<p>Hi {{name}}</p>",
        text: String = "Hi {{name}}",
        variables: List<TemplateVariableDeclaration> = listOf(TemplateVariableDeclaration("name", TemplateVariableType.STRING, required = true, sensitivityTags = listOf("pii"))),
    ) = MessageTemplate(identifier = "t", name = "Test", subjectTemplate = subject, htmlBodyTemplate = html, textBodyTemplate = text, variables = variables, createdAt = 0L)

    @Test
    fun `renders placeholders with html escaping only in the html body`() {
        val rendered = template().render(mapOf("name" to "<Ann & Bob>"))

        assertEquals("Hello <Ann & Bob>", rendered.subject)
        assertEquals("<p>Hi &lt;Ann &amp; Bob&gt;</p>", rendered.htmlBody)
        assertEquals("Hi <Ann & Bob>", rendered.textBody)
        assertEquals(setOf("pii"), rendered.sensitivityTags)
    }

    @Test
    fun `supports conditionals, loops, and format specifiers`() {
        val declarations = listOf(
            TemplateVariableDeclaration("items", TemplateVariableType.LIST),
            TemplateVariableDeclaration("due", TemplateVariableType.TEMPORAL),
            TemplateVariableDeclaration("amount", TemplateVariableType.NUMBER),
            TemplateVariableDeclaration("urgent", TemplateVariableType.BOOLEAN),
        )
        val text = "{{#if urgent}}URGENT {{/if}}Due {{due|MMM d, yyyy}} for {{amount|#,##0.00}}:{{#each items}} [{{.}}]{{/each}}{{#if !items}} none{{/if}}"
        val rendered = template(subject = "", html = "", text = text, variables = declarations).render(
            mapOf("urgent" to true, "due" to LocalDate.of(2026, 8, 28), "amount" to 1234.5, "items" to listOf("a", "{{name}}")),
        )

        assertEquals("URGENT Due Aug 28, 2026 for 1,234.50: [a] [{{name}}]", rendered.textBody)
    }

    @Test
    fun `substituted values are never re-scanned for template syntax`() {
        val declarations = listOf(TemplateVariableDeclaration("name", TemplateVariableType.STRING), TemplateVariableDeclaration("secret", TemplateVariableType.STRING))
        val rendered = template(subject = "", html = "", text = "{{name}}", variables = declarations).render(mapOf("name" to "{{secret}}", "secret" to "leak"))
        assertEquals("{{secret}}", rendered.textBody)
    }

    @Test
    fun `validation reports undeclared variables and broken blocks`() {
        val issues = TemplateRenderer.validate(template(text = "{{missing}} {{#if name}}open {{.}}"))
        val messages = issues.map { it.message }
        assertTrue(messages.any { it.startsWith("Undeclared variable: missing") })
        assertTrue(messages.any { it.startsWith("Unclosed block") })
        assertTrue(messages.any { it.contains("only valid inside") })
        assertFailsWith<TemplateValidationException> { template(text = "{{missing}}").render(mapOf("name" to "x")) }
    }

    @Test
    fun `missing required variables and type mismatches fail rendering`() {
        assertFailsWith<TemplateRenderException> { template().render(emptyMap()) }
        assertFailsWith<TemplateRenderException> { template().render(mapOf("name" to 42)) }
    }

    @Test
    fun `control characters are stripped from the subject`() {
        val rendered = template().render(mapOf("name" to "x\r\nBcc: y"))
        assertEquals("Hello xBcc: y", rendered.subject)
    }

    @Test
    fun `rendered template converts to channel messages`() {
        val rendered = template().render(mapOf("name" to "Ann"))
        val to = listOf(MessageAddress("a@example.com"))
        val email = rendered.toEmailMessage(to, MessageAddress("from@example.com"))
        assertEquals(EmailMessage.HTML_CONTENT_TYPE, email.contentType)
        assertEquals("Hello Ann", email.subject)
        assertEquals("Hi Ann", rendered.toSmsMessage(to).body)
        assertEquals("Hello Ann", rendered.toTeamMessage(to).subject)
        val textOnly = template(html = "").render(mapOf("name" to "Ann")).toEmailMessage(to, MessageAddress("from@example.com"))
        assertEquals(EmailMessage.TEXT_CONTENT_TYPE, textOnly.contentType)
    }
}
