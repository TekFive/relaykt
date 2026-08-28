package org.tekfive.relaykt.template

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

/** Declares a variable a template may reference, with its type and whether it must be supplied. */
data class TemplateVariableDeclaration(
    val name: String,
    val type: TemplateVariableType,
    val required: Boolean = false,
    val description: String? = null,
    val sensitivityTags: List<String> = emptyList(),
) : ToJsonObject {
    companion object : FromJsonObject<TemplateVariableDeclaration>
}
