package org.tekfive.relaykt.team

import org.tekfive.jfk.FromJsonObject
import org.tekfive.relaykt.Attachment
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.MessageAddress

/**
 * A message to a team-collaboration system (Slack, Microsoft Teams, TigerConnect). Recipient
 * addresses are interpreted by the provider: Slack accepts channel names/ids and user emails,
 * TigerConnect accepts user emails and group/role/distribution-list names, Microsoft Teams
 * webhooks target a fixed channel and treat recipients as informational.
 */
class TeamMessage(
    override val to: List<MessageAddress>,
    override val body: String,
    val subject: String? = null,
    override val from: MessageAddress? = null,
    val priority: TeamMessagePriority = TeamMessagePriority.NORMAL,
    override val attachments: List<Attachment> = emptyList(),
) : Message() {

    override val channel: Channel = Channel.TEAM

    init {
        requireRecipients()
    }

    companion object : FromJsonObject<TeamMessage>
}
