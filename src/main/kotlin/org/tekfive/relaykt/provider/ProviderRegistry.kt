package org.tekfive.relaykt.provider

import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.email.sendgrid.SendGridProvider
import org.tekfive.relaykt.email.smtp.SmtpProvider
import org.tekfive.relaykt.email.zeptomail.ZeptoMailProvider
import org.tekfive.relaykt.sms.twilio.TwilioSmsProvider
import org.tekfive.relaykt.team.msteams.MicrosoftTeamsProvider
import org.tekfive.relaykt.team.slack.SlackProvider
import org.tekfive.relaykt.team.tigerconnect.TigerConnectProvider
import org.tekfive.relaykt.testing.InMemoryProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of available [Provider]s keyed by [Provider.id].
 *
 * The built-in providers are registered on first use. Applications can register their own
 * providers (or replace a built-in one) with [register]; endpoints then refer to them by id.
 */
object ProviderRegistry {

    private val providers = ConcurrentHashMap<String, Provider<*>>()

    init {
        registerBuiltIns()
    }

    fun register(provider: Provider<*>) {
        require(provider.id.isNotBlank()) { "Provider id must not be blank" }
        providers[provider.id] = provider
    }

    fun unregister(providerId: String) {
        providers.remove(providerId)
    }

    fun find(providerId: String): Provider<*>? = providers[providerId]

    fun get(providerId: String): Provider<*> {
        return providers[providerId]
            ?: throw IllegalArgumentException("No provider registered with id '$providerId'")
    }

    /** Resolves the provider for [providerId] and checks that it serves [channel]. */
    @Suppress("UNCHECKED_CAST")
    fun <M : Message> get(providerId: String, channel: Channel): Provider<M> {
        val provider = get(providerId)
        check(provider.channel == channel) {
            "Provider '$providerId' serves ${provider.channel.displayName} messages, not ${channel.displayName}"
        }
        return provider as Provider<M>
    }

    fun all(): Collection<Provider<*>> = providers.values.toList()

    fun forChannel(channel: Channel): List<Provider<*>> = providers.values.filter { it.channel == channel }

    /** Restores the built-in provider set, dropping any application-registered providers. */
    fun reset() {
        providers.clear()
        registerBuiltIns()
    }

    private fun registerBuiltIns() {
        register(SmtpProvider)
        register(SendGridProvider)
        register(ZeptoMailProvider)
        register(TwilioSmsProvider)
        register(SlackProvider)
        register(TigerConnectProvider)
        register(MicrosoftTeamsProvider)
        register(InMemoryProvider.email)
        register(InMemoryProvider.sms)
        register(InMemoryProvider.team)
    }
}
