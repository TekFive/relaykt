package org.tekfive.relaykt.team.tigerconnect

import org.tekfive.relaykt.MessageAddress
import org.tekfive.relaykt.provider.ProviderException

data class TigerConnectResolvedRecipient(val recipient: MessageAddress, val targetType: String, val targetId: String)

data class TigerConnectResolution(val resolved: List<TigerConnectResolvedRecipient>, val unresolved: List<MessageAddress>)

/** Resolves exact user addresses or directory names; explicit type:token addresses bypass lookup. */
open class TigerConnectRecipientResolver(private val client: TigerConnectClient) {

    open fun resolveAll(recipients: List<MessageAddress>): TigerConnectResolution {
        val resolved = mutableListOf<TigerConnectResolvedRecipient>()
        val unresolved = mutableListOf<MessageAddress>()
        for (recipient in recipients) {
            // Auth and transient failures propagate rather than masquerading as missing recipients.
            val result = resolve(recipient)
            if (result != null) resolved.add(result) else unresolved.add(recipient)
        }
        return TigerConnectResolution(resolved, unresolved)
    }

    fun resolve(recipient: MessageAddress): TigerConnectResolvedRecipient? {
        val address = recipient.address
        val type = address.substringBefore(':')
        if (type in setOf("user", "group", "role", "distribution_list") && ':' in address) {
            val token = address.substringAfter(':').trim()
            require(token.isNotBlank()) { "TigerConnect recipient token must not be blank" }
            return TigerConnectResolvedRecipient(recipient, type, token)
        }
        if ('@' in address || address.startsWith('+') || TOKEN_REGEX.matches(address)) {
            val record = client.findUser(address) ?: return null
            return record.id?.takeIf { it.isNotBlank() }?.let { TigerConnectResolvedRecipient(recipient, "user", it) }
        }
        // Resolve across all name types so an overlapping group/role/list is not chosen arbitrarily.
        val candidates = listOf(
            "group" to client.findGroupsByName(address),
            "role" to client.findRolesByName(address),
            "distribution_list" to client.findDistributionListsByName(address),
        ).flatMap { (targetType, records) ->
            records.filter { !it.id.isNullOrBlank() && it.name?.equals(address, ignoreCase = true) == true }
                .map { TigerConnectResolvedRecipient(recipient, targetType, it.id!!) }
        }.distinctBy { it.targetType to it.targetId }
        if (candidates.size > 1) throw ProviderException("TigerConnect recipient name is ambiguous; use an explicit type:token address")
        return candidates.singleOrNull()
    }

    private companion object {
        val TOKEN_REGEX = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}
