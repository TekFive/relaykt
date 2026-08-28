package org.tekfive.relaykt.sms

import org.tekfive.jfk.FromJsonObject
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.MessageAddress

/**
 * A text message. [from] is optional because providers such as Twilio can send from a messaging
 * service configured on the endpoint instead of a fixed number.
 */
class SmsMessage(
    override val to: List<MessageAddress>,
    override val body: String,
    override val from: MessageAddress? = null,
) : Message() {

    override val channel: Channel = Channel.SMS

    init {
        requireRecipients()
        require(body.isNotBlank()) { "SMS body must not be blank" }
    }

    companion object : FromJsonObject<SmsMessage>
}
