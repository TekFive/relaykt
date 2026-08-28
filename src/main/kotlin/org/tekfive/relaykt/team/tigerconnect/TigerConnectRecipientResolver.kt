package org.tekfive.relaykt.team.tigerconnect

import org.slf4j.LoggerFactory
import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.provider.ProviderException

data class TigerConnectResolvedRecipient(val recipient: MessageAddress, val targetType: String, val targetId: String)

data class TigerConnectResolution(val resolved: List<TigerConnectResolvedRecipient>, val unresolved: List<MessageAddress>)

/**
 * Resolves a message address to a TigerConnect target by trying, in order: user (by email), group,
 * role, then distribution list (by exact, case-insensitive name).
 */
open class TigerConnectRecipientResolver(private val client: TigerConnectClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    open fun resolveAll(recipients: List<MessageAddress>): TigerConnectResolution {
        val resolved = mutableListOf<TigerConnectResolvedRecipient>()
        val unresolved = mutableListOf<MessageAddress>()
        for (recipient in recipients) {
            // Transient failures (I/O errors, 408/429/5xx) propagate so Relay.classify can retry.
            val result = resolve(recipient)
            if (result != null) resolved.add(result) else unresolved.add(recipient)
        }
        return TigerConnectResolution(resolved, unresolved)
    }

    fun resolve(recipient: MessageAddress): TigerConnectResolvedRecipient? {
        val address = recipient.address.trim()
        return lookupOrNull("user") { resolveUser(recipient, address) }
            ?: lookupOrNull("group") { resolveByName(recipient, "group", client.findGroupsByName(address)) }
            ?: lookupOrNull("role") { resolveByName(recipient, "role", client.findRolesByName(address)) }
            ?: lookupOrNull("distribution list") { resolveByName(recipient, "distribution_list", client.findDistributionListsByName(address)) }
    }

    private fun resolveUser(recipient: MessageAddress, address: String): TigerConnectResolvedRecipient? {
        if (!address.contains("@")) {
            return null
        }
        val id = client.findUsersByEmail(address).firstNotNullOfOrNull { it.id } ?: return null
        return TigerConnectResolvedRecipient(recipient, "user", id)
    }

    /** Resolves only when exactly one distinct record matches the name; multiple matches are ambiguous. */
    private fun resolveByName(recipient: MessageAddress, targetType: String, records: List<TigerConnectRecord>): TigerConnectResolvedRecipient? {
        val requested = recipient.address.trim()
        val matchingIds = records.filter { it.id != null && it.name?.equals(requested, ignoreCase = true) == true }
            .map { it.id!! }.distinct()
        if (matchingIds.size > 1) {
            log.warn("TigerConnect {} lookup returned {} exact name matches; treating recipient as unresolved", targetType, matchingIds.size)
            return null
        }
        return matchingIds.firstOrNull()?.let { TigerConnectResolvedRecipient(recipient, targetType, it) }
    }

    /**
     * A definitive 4xx from an earlier lookup type continues to the next lookup rather than
     * aborting the recipient; transient failures propagate.
     */
    private fun lookupOrNull(lookupType: String, lookup: () -> TigerConnectResolvedRecipient?): TigerConnectResolvedRecipient? {
        return try {
            lookup()
        } catch (e: ProviderException) {
            if (e.isTransient) throw e
            log.warn("TigerConnect {} lookup failed; continuing to next lookup type", lookupType, e)
            null
        }
    }
}
