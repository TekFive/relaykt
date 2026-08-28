package org.tekfive.relaykt

import org.tekfive.jfk.ToJsonObject

/**
 * Base type for everything RelayKt can deliver. Concrete messages live in their channel package
 * ([org.tekfive.relaykt.email.EmailMessage], [org.tekfive.relaykt.sms.SmsMessage],
 * [org.tekfive.relaykt.team.TeamMessage]).
 *
 * Messages are immutable value objects and serialize to JSON through JFK so they can be persisted
 * in the delivery queue and restored later with [Channel.readMessage].
 */
abstract class Message : ToJsonObject {

    abstract val channel: Channel

    /** Primary recipients. Every channel requires at least one. */
    abstract val to: List<MessageAddress>

    /** Sender identity. Some providers (Twilio messaging services, Slack bots) supply their own. */
    abstract val from: MessageAddress?

    abstract val body: String

    /** File attachments; providers advertise support through [Capability.ATTACHMENTS]. */
    open val attachments: List<Attachment>
        get() = emptyList()

    /** Every address the message is delivered to, including secondary recipients such as CC/BCC. */
    open val allRecipients: List<MessageAddress>
        get() = to

    /** Total attachment payload in bytes. */
    val attachmentsSizeBytes: Long
        get() = attachments.sumOf { it.content.size.toLong() }

    protected fun requireRecipients() {
        require(to.isNotEmpty()) { "${channel.displayName} messages require at least one recipient" }
    }
}
