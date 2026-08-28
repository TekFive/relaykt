package org.tekfive.relaykt.team.msteams

import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.jfk.jsonArray
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessagePriority
import java.util.UUID

/**
 * Posts team messages to a Microsoft Teams channel through a webhook as an Adaptive Card.
 *
 * A webhook targets one fixed channel, so recipient addresses are informational: they are rendered
 * into the card as an "Attention" line rather than resolved. Priority is rendered as a card
 * header colour and label. Teams webhooks return no message id or status.
 */
object MicrosoftTeamsProvider : Provider<TeamMessage> {

    internal var clientFactory: (MicrosoftTeamsConfiguration) -> MicrosoftTeamsClient = { MicrosoftTeamsClient(it) }

    override val id: String = "msteams"

    override val channel: Channel = Channel.TEAM

    override val capabilities: Set<Capability> = setOf(Capability.PRIORITY, Capability.MULTIPLE_RECIPIENTS)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(MicrosoftTeamsConfiguration, configuration)
    }

    override fun send(message: TeamMessage, configuration: JsonObject): SendResult {
        val client = clientFactory(ProviderConfigurations.parse(MicrosoftTeamsConfiguration, configuration))
        val messageId = UUID.randomUUID().toString()
        client.post(buildEnvelope(message))
        return SendResult(messageId = messageId, providerId = id, status = DeliveryStatus.SENT)
    }

    internal fun buildEnvelope(message: TeamMessage): JsonObject {
        val bodyElements = jsonArray {
            message.subject?.takeIf { it.isNotBlank() }?.let { subject ->
                addObject {
                    "type" set "TextBlock"
                    "text" set subject
                    "weight" set "Bolder"
                    "size" set "Medium"
                    "wrap" set true
                    priorityColor(message.priority)?.let { "color" set it }
                }
            }
            if (message.priority != TeamMessagePriority.NORMAL) {
                addObject {
                    "type" set "TextBlock"
                    "text" set "Priority: ${message.priority.name.lowercase().replaceFirstChar { it.uppercase() }}"
                    "weight" set "Bolder"
                    "color" set (priorityColor(message.priority) ?: "Default")
                    "wrap" set true
                }
            }
            addObject {
                "type" set "TextBlock"
                "text" set message.body
                "wrap" set true
            }
            if (message.to.isNotEmpty()) {
                addObject {
                    "type" set "TextBlock"
                    "text" set "Attention: " + message.to.joinToString(", ") { it.displayName ?: it.address }
                    "isSubtle" set true
                    "size" set "Small"
                    "wrap" set true
                }
            }
        }

        val card = json {
            "\$schema" set "http://adaptivecards.io/schemas/adaptive-card.json"
            "type" set "AdaptiveCard"
            "version" set "1.4"
            "body" set bodyElements
        }

        return json {
            "type" set "message"
            "attachments" set jsonArray {
                addObject {
                    "contentType" set "application/vnd.microsoft.card.adaptive"
                    "contentUrl" set null
                    "content" set card
                }
            }
        }
    }

    private fun priorityColor(priority: TeamMessagePriority): String? = when (priority) {
        TeamMessagePriority.NORMAL -> null
        TeamMessagePriority.HIGH -> "Warning"
        TeamMessagePriority.URGENT -> "Attention"
    }
}
