package org.tekfive.relaykt.endpoint

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderRegistry

/**
 * A configured route to a provider: "the transactional SendGrid account", "the on-call Slack
 * workspace". Endpoints are owned by the application (typically stored in its own tables) and are
 * handed to RelayKt at send time or looked up by [id] through an [EndpointResolver] when a queued
 * message is delivered.
 *
 * @property id application-defined identifier, persisted with queued messages and receipts.
 * @property providerId id of a provider registered in [ProviderRegistry].
 * @property configuration provider-specific settings and credentials (see each provider's `Configuration` class).
 * @property maxAttachmentsSizeBytes per-endpoint override of the global attachment size limit.
 */
class Endpoint(
    val id: String,
    val providerId: String,
    val configuration: JsonObject,
    val maxAttachmentsSizeBytes: Long? = null,
) {

    init {
        require(id.isNotBlank()) { "Endpoint id must not be blank" }
        require(providerId.isNotBlank()) { "Endpoint provider id must not be blank" }
    }

    val provider: Provider<*>
        get() = ProviderRegistry.get(providerId)

    val channel: Channel
        get() = provider.channel

    fun <M : Message> providerFor(channel: Channel): Provider<M> = ProviderRegistry.get(providerId, channel)

    /** Never prints the configuration: it holds credentials. */
    override fun toString(): String = "Endpoint(id=$id, providerId=$providerId)"
}
