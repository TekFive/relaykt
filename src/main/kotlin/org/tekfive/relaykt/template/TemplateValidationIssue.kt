package org.tekfive.relaykt.template

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

data class TemplateValidationIssue(
    val placeholder: String,
    val location: String,
    val message: String,
) : ToJsonObject {
    companion object : FromJsonObject<TemplateValidationIssue>
}
