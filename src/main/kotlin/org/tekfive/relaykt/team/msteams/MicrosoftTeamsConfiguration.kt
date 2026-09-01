package org.tekfive.relaykt.team.msteams

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.provider.SecureUrls
import org.tekfive.relaykt.tls.TlsConfiguration

/**
 * Endpoint configuration for [MicrosoftTeamsProvider]. [webhookUrl] is an Incoming Webhook or a
 * Power Automate "post to a channel when a webhook request is received" URL; both accept the
 * Adaptive Card envelope the provider posts. The URL embeds a secret, so it never prints.
 */
class MicrosoftTeamsConfiguration(
    val webhookUrl: String,
    val tls: TlsConfiguration = TlsConfiguration(),
) : ToJsonObject {

    init {
        require(webhookUrl.isNotBlank()) { "Microsoft Teams webhookUrl is required" }
        SecureUrls.requireHttps(webhookUrl, "Microsoft Teams webhookUrl")
        tls.validateForUrl(webhookUrl, "Microsoft Teams webhookUrl")
    }

    override fun toString(): String = "MicrosoftTeamsConfiguration(webhookUrl=REDACTED, tls=$tls)"

    companion object : FromJsonObject<MicrosoftTeamsConfiguration>
}
