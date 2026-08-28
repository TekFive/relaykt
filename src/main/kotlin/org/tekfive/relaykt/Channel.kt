package org.tekfive.relaykt

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.keep.data.DataEnum
import org.tekfive.keep.data.DataEnumColumnType
import org.tekfive.relaykt.email.EmailMessage
import org.tekfive.relaykt.sms.SmsMessage
import org.tekfive.relaykt.team.TeamMessage

/**
 * The delivery channel a [Message] travels over. Each channel has exactly one message type; the
 * channel is persisted with queued messages so the payload can be deserialized back into the
 * right [Message] subtype.
 */
enum class Channel(
    override val id: Int,
    override val displayName: String,
    private val messageReader: FromJsonObject<out Message>,
) : DataEnum {
    EMAIL(1, "Email", EmailMessage),
    SMS(2, "SMS", SmsMessage),
    TEAM(3, "Team Message", TeamMessage),
    ;

    /** Deserializes a message previously produced by [Message.toJsonObject] for this channel. */
    fun readMessage(json: JsonObject): Message = messageReader.fromJson(json)

    companion object : DataEnumColumnType<Channel>()
}
