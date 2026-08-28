package org.tekfive.relaykt.team.slack

import org.slf4j.LoggerFactory
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.provider.ProviderException

data class SlackResolvedRecipient(val recipient: MessageAddress, val channelId: String)

data class SlackResolution(val resolved: List<SlackResolvedRecipient>, val unresolved: List<MessageAddress>)

/**
 * Turns message addresses into Slack conversation ids. Accepts channel ids (`C…`/`G…`/`D…`), user
 * ids (`U…`/`W…`, opened as a DM), user emails (looked up then opened as a DM), and channel names.
 */
open class SlackRecipientResolver(private val client: SlackClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    open fun resolveAll(recipients: List<MessageAddress>): SlackResolution {
        val resolved = mutableListOf<SlackResolvedRecipient>()
        val unresolved = mutableListOf<MessageAddress>()
        for (recipient in recipients) {
            // Only definitive lookup failures make a recipient unresolved. Transient failures
            // (I/O errors, 408/429/5xx) propagate so Relay.classify can schedule a retry.
            val result = try {
                resolve(recipient)
            } catch (e: ProviderException) {
                if (e.isTransient) throw e
                log.warn("Slack recipient lookup failed; treating 1 recipient as unresolved", e)
                null
            }
            if (result != null) resolved.add(result) else unresolved.add(recipient)
        }
        return SlackResolution(resolved, unresolved)
    }

    fun resolve(recipient: MessageAddress): SlackResolvedRecipient? {
        val address = recipient.address.trim()
        extractExplicitId(address)?.let { explicitId ->
            resolveExplicitId(recipient, explicitId)?.let { return it }
            // Id lookup failed: fall through to name-based resolution.
        }
        if (address.contains("@")) {
            val userId = client.lookupUserByEmail(address) ?: return null
            val channelId = client.openConversation(userId) ?: return null
            return SlackResolvedRecipient(recipient, channelId)
        }
        return client.findConversationByName(address)?.let { SlackResolvedRecipient(recipient, it) }
    }

    private fun resolveExplicitId(recipient: MessageAddress, explicitId: String): SlackResolvedRecipient? {
        return try {
            if (USER_ID_REGEX.matches(explicitId)) {
                client.openConversation(explicitId)?.let { SlackResolvedRecipient(recipient, it) }
            } else {
                SlackResolvedRecipient(recipient, explicitId)
            }
        } catch (e: ProviderException) {
            if (e.isTransient) throw e
            log.warn("Slack id lookup failed for 1 recipient; falling back to name-based resolution", e)
            null
        }
    }

    private fun extractExplicitId(address: String): String? {
        val stripped = address.removePrefix("<#").removePrefix("<@").removePrefix("#").removePrefix("@")
            .substringBefore("|").removeSuffix(">")
        return stripped.takeIf { CONVERSATION_ID_REGEX.matches(it) || USER_ID_REGEX.matches(it) }
    }

    companion object {
        // Slack ids look like C0123ABCD / U0123ABCD: uppercase alphanumeric, at least 9 characters.
        // Anything looser misclassifies plain names (e.g. "General") as ids.
        private val CONVERSATION_ID_REGEX = Regex("^[CGD][A-Z0-9]{8,}$")
        private val USER_ID_REGEX = Regex("^[UW][A-Z0-9]{8,}$")
    }
}
