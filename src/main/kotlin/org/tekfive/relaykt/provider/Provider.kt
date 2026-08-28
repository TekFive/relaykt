package org.tekfive.relaykt.provider

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.SendResult

/**
 * A stateless integration with an external delivery system (SMTP server, Twilio, Slack, ...).
 *
 * Providers are singletons keyed by [id] in [ProviderRegistry]. All connection details come from
 * the [org.tekfive.relaykt.endpoint.Endpoint.configuration] passed to each call, so one provider
 * can serve any number of endpoints (multiple SMTP servers, multiple Slack workspaces).
 *
 * Implementations should throw [ProviderException] for API failures and let I/O exceptions
 * propagate; [org.tekfive.relaykt.Relay] classifies both for retry.
 */
interface Provider<M : Message> {

    /** Stable identifier persisted with endpoints and receipts (e.g. `"smtp"`, `"twilio-sms"`). */
    val id: String

    val channel: Channel

    val capabilities: Set<Capability>

    /** Parses and validates [configuration], throwing [IllegalArgumentException]/[IllegalStateException] on problems. */
    fun validateConfiguration(configuration: JsonObject) {
    }

    fun send(message: M, configuration: JsonObject): SendResult

    /**
     * Looks up delivery status for a message id returned by [send]. Returns null when the message
     * cannot be found or the provider lacks [Capability.STATUS_LOOKUP].
     */
    fun status(messageId: String, configuration: JsonObject): DeliveryStatus? = null

    fun supports(capability: Capability): Boolean = capability in capabilities
}
