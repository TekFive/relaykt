package org.tekfive.relaykt.template

import org.tekfive.keep.data.Data
import org.tekfive.keep.db.dbTransactionAt

/**
 * A stored template with declared variables and subject / HTML body / text body sources.
 *
 * Templates are channel-neutral: [RenderedTemplate] produces the subject and both body flavours,
 * and the application picks which parts feed which message type ([RenderedTemplate.toEmailMessage],
 * [RenderedTemplate.toSmsMessage], [RenderedTemplate.toTeamMessage]).
 */
class MessageTemplate(
    val identifier: String,
    var name: String,
    var description: String? = null,
    var subjectTemplate: String = "",
    var htmlBodyTemplate: String = "",
    var textBodyTemplate: String = "",
    var variables: List<TemplateVariableDeclaration> = emptyList(),
    var validationIssues: List<TemplateValidationIssue> = emptyList(),
    var active: Boolean = true,
    val createdAt: Long = dbTransactionAt(),
) : Data() {

    init {
        require(identifier.isNotBlank()) { "Template identifier must not be blank" }
    }

    /** Renders with [TemplateRenderer]. */
    fun render(variables: Map<String, Any>): RenderedTemplate = TemplateRenderer.render(this, variables)
}
