package org.tekfive.relaykt.template

import org.tekfive.keep.data.DataTable
import org.tekfive.keep.data.active
import org.tekfive.keep.data.createdAt
import org.tekfive.keep.data.description
import org.tekfive.keep.data.name
import org.tekfive.keep.data.uniqueIndexWithStandardName
import org.tekfive.keep.json.toFromJsonArray

object MessageTemplateTable : DataTable<MessageTemplate>("relay_message_templates") {
    val identifier = varchar("identifier", 100).uniqueIndexWithStandardName()
    val name = name()
    val description = description()
    val subjectTemplate = text("subject_template")
    val htmlBodyTemplate = text("html_body_template")
    val textBodyTemplate = text("text_body_template")
    val variables = toFromJsonArray("variables", TemplateVariableDeclaration)
    val validationIssues = toFromJsonArray("validation_issues", TemplateValidationIssue)
    val active = active()
    val createdAt = createdAt()

    fun findByIdentifier(identifier: String): MessageTemplate? = findByUnique(identifier, MessageTemplateTable.identifier)
}
