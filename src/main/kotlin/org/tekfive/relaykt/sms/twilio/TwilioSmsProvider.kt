package org.tekfive.relaykt.sms.twilio

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import org.tekfive.relaykt.sms.SmsMessage

/** SMS through Twilio Programmable Messaging. Delivers to exactly one recipient per send. */
object TwilioSmsProvider : Provider<SmsMessage> {

    internal var clientFactory: (TwilioSmsConfiguration) -> TwilioSmsClient = { TwilioSmsClient(it) }

    override val id: String = "twilio-sms"

    override val channel: Channel = Channel.SMS

    override val capabilities: Set<Capability> = setOf(Capability.STATUS_LOOKUP)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(TwilioSmsConfiguration, configuration)
    }

    override fun send(message: SmsMessage, configuration: JsonObject): SendResult {
        val twilioConfiguration = ProviderConfigurations.parse(TwilioSmsConfiguration, configuration)
        val recipient = message.to.singleOrNull()
            ?: throw IllegalArgumentException("Twilio SMS supports exactly one recipient per send")

        val from = message.from?.address ?: twilioConfiguration.normalizedFromNumber
        val messagingServiceSid = if (from == null) twilioConfiguration.normalizedMessagingServiceSid else null
        if (from == null && messagingServiceSid == null) {
            throw IllegalStateException("Twilio SMS requires a from address on the message or a fromNumber/messagingServiceSid in the endpoint configuration")
        }

        val response = clientFactory(twilioConfiguration).send(
            TwilioSmsSendRequest(to = recipient.address, body = message.body, from = from, messagingServiceSid = messagingServiceSid),
        )
        return SendResult(messageId = response.sid.orEmpty(), providerId = id, status = mapStatus(response.status))
    }

    override fun status(messageId: String, configuration: JsonObject): DeliveryStatus? {
        val response = clientFactory(ProviderConfigurations.parse(TwilioSmsConfiguration, configuration)).getMessage(messageId) ?: return null
        return mapStatus(response.status)
    }

    internal fun mapStatus(status: String?): DeliveryStatus = when (status?.lowercase()) {
        "accepted", "queued", "scheduled" -> DeliveryStatus.QUEUED
        // partially_delivered: some segments reached the handset but delivery is incomplete.
        "sending", "sent", "partially_delivered" -> DeliveryStatus.SENT
        // read: WhatsApp-channel receipt.
        "delivered" -> DeliveryStatus.DELIVERED
        "read" -> DeliveryStatus.READ
        "failed", "undelivered", "canceled" -> DeliveryStatus.FAILED
        // Inbound-only statuses (receiving, received) intentionally fall through to UNKNOWN.
        else -> DeliveryStatus.UNKNOWN
    }
}
