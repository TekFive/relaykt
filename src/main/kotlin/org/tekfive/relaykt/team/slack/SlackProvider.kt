package org.tekfive.relaykt.team.slack

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessageSupport

/**
 * Posts team messages to Slack channels and direct messages through a bot token. Slack has no
 * message-status API, so [status] is unsupported.
 */
object SlackProvider : Provider<TeamMessage> {

    internal var clientFactory: (SlackConfiguration) -> SlackClient = { SlackClient(it) }

    override val id: String = "slack"

    override val channel: Channel = Channel.TEAM

    override val capabilities: Set<Capability> = setOf(Capability.MULTIPLE_RECIPIENTS)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(SlackConfiguration, configuration)
    }

    override fun send(message: TeamMessage, configuration: JsonObject): SendResult {
        val client = clientFactory(ProviderConfigurations.parse(SlackConfiguration, configuration))
        val resolution = SlackRecipientResolver(client).resolveAll(message.to)

        // Fail fast before posting anything: sending to a resolved subset would be silent partial
        // delivery, and receipts would be recorded for recipients never sent to.
        if (resolution.unresolved.isNotEmpty()) {
            throw RelayException("Slack could not resolve ${resolution.unresolved.size} of ${message.to.size} recipients")
        }

        val text = renderText(message)
        val messageIds = linkedMapOf<String, String>()
        for (resolved in resolution.resolved) {
            try {
                messageIds[resolved.recipient.address] = client.postMessage(resolved.channelId, text).messageId
            } catch (e: Exception) {
                throw TeamMessageSupport.partialFailure("Slack", e, sentCount = messageIds.size, totalCount = resolution.resolved.size)
            }
        }
        return SendResult(
            messageId = TeamMessageSupport.aggregateMessageIds(messageIds.values.toList()),
            providerId = id,
            status = DeliveryStatus.SENT,
            recipientMessageIds = messageIds,
        )
    }

    internal fun renderText(message: TeamMessage): String {
        val subject = message.subject?.takeIf { it.isNotBlank() } ?: return message.body
        return "*$subject*\n\n${message.body}"
    }
}
