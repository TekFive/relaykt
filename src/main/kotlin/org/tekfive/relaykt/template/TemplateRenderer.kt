package org.tekfive.relaykt.template

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/** Thrown when a template cannot be rendered due to missing required variables or other runtime errors. */
class TemplateRenderException(message: String) : RuntimeException(message)

/** Thrown when a template has unresolved validation issues that prevent rendering. */
class TemplateValidationException(val issues: List<TemplateValidationIssue>) :
    RuntimeException("Template has ${issues.size} validation issue(s)")

/**
 * Stateless renderer that validates and renders [MessageTemplate] instances by substituting
 * declared variables into subject, HTML body, and text body templates.
 *
 * Syntax:
 * - `{{name}}` and `{{name|format}}` placeholders (format specifiers for NUMBER, TEMPORAL, BOOLEAN)
 * - `{{#if name}}...{{/if}}` and `{{#if !name}}...{{/if}}` conditionals
 * - `{{#each list}}...{{.}}...{{/each}}` loops
 *
 * Injection safety: substituted values are never re-scanned for placeholder or block-tag syntax.
 * Loop blocks are expanded into side buffers and re-attached via opaque tokens after all
 * placeholder substitution has completed, and every substitution uses lambda-based
 * `Regex.replace` (which does not re-scan its own output). HTML body values are HTML-escaped.
 */
object TemplateRenderer {

    /** Matches {{variable}}, {{variable|format}}, and {{.}} placeholders, excluding block tags. */
    private val PLACEHOLDER_REGEX = Regex("""\{\{([^{}#/][^{}]*?)\}\}""")

    private val IF_OPEN_TAG_REGEX = Regex("""\{\{\s*#if\s+(!?)(\w+)\s*\}\}""")
    private val IF_CLOSE_TAG_REGEX = Regex("""\{\{\s*/if\s*\}\}""")
    private val EACH_OPEN_TAG_REGEX = Regex("""\{\{\s*#each\s+(\w+)\s*\}\}""")
    private val EACH_CLOSE_TAG_REGEX = Regex("""\{\{\s*/each\s*\}\}""")

    private val IF_BLOCK_REGEX = Regex("""\{\{\s*#if\s+(!?)(\w+)\s*\}\}(.*?)\{\{\s*/if\s*\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val EACH_BLOCK_REGEX = Regex("""\{\{\s*#each\s+(\w+)\s*\}\}(.*?)\{\{\s*/each\s*\}\}""", RegexOption.DOT_MATCHES_ALL)

    /** ASCII control characters, i.e. 0x00-0x1F and 0x7F (defense in depth against SMTP header injection in subjects). */
    private val ASCII_CONTROL_CHARS_REGEX = Regex("""\p{Cntrl}""")

    /**
     * Delimiter for opaque loop-expansion tokens (a private-use code point). It is stripped from
     * template input and from every substituted value, so neither a template author nor a variable
     * value can forge a token.
     */
    private val LOOP_TOKEN_DELIMITER: String = Char(0xE000).toString()

    /**
     * Validates the template: duplicate variable declarations, block-tag structure (unclosed,
     * orphaned, and nested blocks), block-tag variable names, {{.}} scope, undeclared placeholder
     * variables, and format specifiers.
     */
    fun validate(template: MessageTemplate): List<TemplateValidationIssue> {
        val issues = mutableListOf<TemplateValidationIssue>()

        val declaredVars = mutableMapOf<String, TemplateVariableDeclaration>()
        for (decl in template.variables) {
            if (declaredVars.containsKey(decl.name)) {
                issues.add(TemplateValidationIssue("{{${decl.name}}}", "variables", "Duplicate variable declaration: ${decl.name}"))
            } else {
                declaredVars[decl.name] = decl
            }
        }

        validateField(template.subjectTemplate, "subject", declaredVars, issues)
        validateField(template.htmlBodyTemplate, "htmlBody", declaredVars, issues)
        validateField(template.textBodyTemplate, "textBody", declaredVars, issues)

        return issues
    }

    /**
     * Renders a template. Validation always runs fresh (stored validation state may be stale), then
     * required variables and value types are checked, each field is rendered, ASCII control
     * characters are stripped from the subject, and sensitivity tags of present variables are collected.
     */
    fun render(template: MessageTemplate, variables: Map<String, Any>): RenderedTemplate {
        val issues = validate(template)
        if (issues.isNotEmpty()) {
            throw TemplateValidationException(issues)
        }

        val declaredVars = template.variables.associateBy { it.name }

        for (decl in template.variables) {
            if (decl.required && !variables.containsKey(decl.name)) {
                throw TemplateRenderException("Missing required variable: ${decl.name}")
            }
        }
        for (decl in template.variables) {
            val value = variables[decl.name] ?: continue
            validateValueType(value, decl.type)?.let { throw TemplateRenderException("Variable '${decl.name}': $it") }
        }

        val subject = ASCII_CONTROL_CHARS_REGEX.replace(renderField(template.subjectTemplate, variables, declaredVars, htmlEscape = false), "")
        val htmlBody = renderField(template.htmlBodyTemplate, variables, declaredVars, htmlEscape = true)
        val textBody = renderField(template.textBodyTemplate, variables, declaredVars, htmlEscape = false)

        val sensitivityTags = template.variables.filter { variables.containsKey(it.name) }.flatMap { it.sensitivityTags }.toSet()

        return RenderedTemplate(subject = subject, htmlBody = htmlBody, textBody = textBody, sensitivityTags = sensitivityTags)
    }

    private fun renderField(
        content: String,
        variables: Map<String, Any>,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        htmlEscape: Boolean,
    ): String {
        // The token delimiter must not be forgeable from template text.
        val sanitized = content.replace(LOOP_TOKEN_DELIMITER, "")
        val afterConditionals = processConditionals(sanitized, variables)

        // Expand each loop block fully and replace the block with an opaque token.
        val loopExpansions = mutableMapOf<String, String>()
        var loopIndex = 0
        val afterLoops = EACH_BLOCK_REGEX.replace(afterConditionals) { match ->
            val varName = match.groupValues[1]
            val body = match.groupValues[2]
            val items = (variables[varName] as? List<*>).orEmpty()
            val expanded = StringBuilder()
            for (item in items) {
                expanded.append(substituteValues(body, variables, declaredVars, htmlEscape, item?.toString() ?: ""))
            }
            val token = "${LOOP_TOKEN_DELIMITER}LOOP${loopIndex++}${LOOP_TOKEN_DELIMITER}"
            loopExpansions[token] = expanded.toString()
            token
        }

        var result = substituteValues(afterLoops, variables, declaredVars, htmlEscape)
        // Re-attach the loop expansions with literal string replacement — no re-scan.
        for ((token, expansion) in loopExpansions) {
            result = result.replace(token, expansion)
        }
        return result
    }

    private fun processConditionals(content: String, variables: Map<String, Any>): String {
        return IF_BLOCK_REGEX.replace(content) { match ->
            val negated = match.groupValues[1] == "!"
            val truthy = isTruthy(variables[match.groupValues[2]])
            if (if (negated) !truthy else truthy) match.groupValues[3] else ""
        }
    }

    /** Handlebars-like truthiness: null, false, zero, empty strings, and empty lists are falsy. */
    private fun isTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    private fun substituteValues(
        content: String,
        variables: Map<String, Any>,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        htmlEscape: Boolean,
        loopItemText: String? = null,
    ): String {
        return PLACEHOLDER_REGEX.replace(content) { match ->
            val parts = match.groupValues[1].trim().split("|", limit = 2)
            val varName = parts[0].trim()
            val formatSpec = parts.getOrNull(1)?.trim()

            val formatted = if (varName == ".") {
                loopItemText ?: ""
            } else {
                formatValue(varName, variables[varName], declaredVars[varName]?.type, formatSpec)
            }
            val cleaned = formatted.replace(LOOP_TOKEN_DELIMITER, "")
            if (htmlEscape) escapeHtml(cleaned) else cleaned
        }
    }

    private fun formatValue(varName: String, value: Any?, type: TemplateVariableType?, formatSpec: String?): String {
        if (value == null) {
            return ""
        }
        if (type == TemplateVariableType.LIST && value is List<*>) {
            return value.joinToString(", ") { it?.toString() ?: "" }
        }
        if (formatSpec != null && type != null) {
            try {
                return applyFormatSpecifier(value, type, formatSpec)
            } catch (e: DateTimeException) {
                // Never include the value — it may be PHI.
                throw TemplateRenderException("Variable '$varName': value cannot be formatted with pattern '$formatSpec'")
            } catch (e: IllegalArgumentException) {
                throw TemplateRenderException("Variable '$varName': value cannot be formatted with pattern '$formatSpec'")
            }
        }
        return value.toString()
    }

    private fun applyFormatSpecifier(value: Any, type: TemplateVariableType, formatSpec: String): String = when (type) {
        TemplateVariableType.NUMBER -> DecimalFormat(formatSpec, DecimalFormatSymbols(Locale.US)).format(value)
        TemplateVariableType.TEMPORAL -> DateTimeFormatter.ofPattern(formatSpec, Locale.US).format(value as TemporalAccessor)
        TemplateVariableType.BOOLEAN -> {
            val parts = formatSpec.split("/", limit = 2)
            if (value == true) parts[0] else parts.getOrElse(1) { "" }
        }
        else -> value.toString()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;")

    private fun validateValueType(value: Any, type: TemplateVariableType): String? = when (type) {
        TemplateVariableType.STRING -> if (value !is String) "expected String, got ${value::class.simpleName}" else null
        TemplateVariableType.NUMBER -> if (value !is Number) "expected Number, got ${value::class.simpleName}" else null
        TemplateVariableType.BOOLEAN -> if (value !is Boolean) "expected Boolean, got ${value::class.simpleName}" else null
        TemplateVariableType.TEMPORAL -> if (value !is TemporalAccessor) "expected Temporal, got ${value::class.simpleName}" else null
        TemplateVariableType.LIST -> if (value !is List<*>) "expected List, got ${value::class.simpleName}" else null
    }

    private fun validateField(
        content: String,
        location: String,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        validateBlockStructure(content, location, "if", IF_OPEN_TAG_REGEX, IF_CLOSE_TAG_REGEX, declaredVars, issues)
        validateBlockStructure(content, location, "each", EACH_OPEN_TAG_REGEX, EACH_CLOSE_TAG_REGEX, declaredVars, issues)

        // Validate placeholders inside each-block bodies (where {{.}} is allowed), then remove the
        // blocks and validate the remainder (where {{.}} is a stray token).
        val remainder = EACH_BLOCK_REGEX.replace(content) { match ->
            validatePlaceholders(stripBlockTags(match.groupValues[2]), location, declaredVars, insideEach = true, issues)
            ""
        }
        validatePlaceholders(stripBlockTags(remainder), location, declaredVars, insideEach = false, issues)
    }

    private fun stripBlockTags(content: String): String {
        var cleaned = IF_OPEN_TAG_REGEX.replace(content, "")
        cleaned = IF_CLOSE_TAG_REGEX.replace(cleaned, "")
        cleaned = EACH_OPEN_TAG_REGEX.replace(cleaned, "")
        return EACH_CLOSE_TAG_REGEX.replace(cleaned, "")
    }

    private fun validateBlockStructure(
        content: String,
        location: String,
        tagName: String,
        openRegex: Regex,
        closeRegex: Regex,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        // (position, tag text, variable name for openers / null for closers) in document order.
        val events = mutableListOf<Triple<Int, String, String?>>()
        openRegex.findAll(content).forEach { events.add(Triple(it.range.first, it.value, it.groupValues.last())) }
        closeRegex.findAll(content).forEach { events.add(Triple(it.range.first, it.value, null)) }
        events.sortBy { it.first }

        val openStack = ArrayDeque<String>()
        for ((_, tagText, varName) in events) {
            if (varName != null) {
                if (!declaredVars.containsKey(varName)) {
                    issues.add(TemplateValidationIssue(tagText, location, "Undeclared variable: $varName"))
                }
                if (openStack.isNotEmpty()) {
                    issues.add(TemplateValidationIssue(tagText, location, "Nested {{#$tagName}} blocks are not supported"))
                }
                openStack.addLast(tagText)
            } else if (openStack.isEmpty()) {
                issues.add(TemplateValidationIssue(tagText, location, "Orphaned {{/$tagName}} without matching {{#$tagName}}"))
            } else {
                openStack.removeLast()
            }
        }
        for (unclosed in openStack) {
            issues.add(TemplateValidationIssue(unclosed, location, "Unclosed block: $unclosed has no matching {{/$tagName}}"))
        }
    }

    private fun validatePlaceholders(
        content: String,
        location: String,
        declaredVars: Map<String, TemplateVariableDeclaration>,
        insideEach: Boolean,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        for (match in PLACEHOLDER_REGEX.findAll(content)) {
            val parts = match.groupValues[1].trim().split("|", limit = 2)
            val varName = parts[0].trim()
            val formatSpec = parts.getOrNull(1)?.trim()

            if (varName == ".") {
                if (!insideEach) {
                    issues.add(TemplateValidationIssue("{{.}}", location, "{{.}} is only valid inside an {{#each}} block"))
                }
                continue
            }

            val placeholder = if (formatSpec != null) "{{$varName|$formatSpec}}" else "{{$varName}}"
            val declaration = declaredVars[varName]
            if (declaration == null) {
                issues.add(TemplateValidationIssue(placeholder, location, "Undeclared variable: $varName"))
                continue
            }
            if (formatSpec != null) {
                validateFormatSpecifier(placeholder, formatSpec, declaration.type, location, issues)
            }
        }
    }

    private fun validateFormatSpecifier(
        placeholder: String,
        formatSpec: String,
        type: TemplateVariableType,
        location: String,
        issues: MutableList<TemplateValidationIssue>,
    ) {
        if (!type.supportsFormatSpecifier) {
            issues.add(TemplateValidationIssue(placeholder, location, "${type.name} variables do not support format specifiers"))
            return
        }
        when (type) {
            TemplateVariableType.NUMBER -> try {
                DecimalFormat(formatSpec, DecimalFormatSymbols(Locale.US))
            } catch (e: IllegalArgumentException) {
                issues.add(TemplateValidationIssue(placeholder, location, "Invalid number format: $formatSpec"))
            }
            TemplateVariableType.TEMPORAL -> try {
                DateTimeFormatter.ofPattern(formatSpec, Locale.US)
            } catch (e: IllegalArgumentException) {
                issues.add(TemplateValidationIssue(placeholder, location, "Invalid temporal format: $formatSpec"))
            }
            TemplateVariableType.BOOLEAN -> if (!formatSpec.contains("/")) {
                issues.add(TemplateValidationIssue(placeholder, location, "Boolean format must contain '/' separator (e.g. Yes/No)"))
            }
            else -> {}
        }
    }
}
