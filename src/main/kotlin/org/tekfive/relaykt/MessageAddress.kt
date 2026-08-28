package org.tekfive.relaykt

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.jfk.json

/**
 * An address on any channel: an email address, an E.164 phone number, a Slack channel or user id,
 * a TigerConnect user/group/role name, and so on. The same type is used for senders and recipients.
 *
 * Surrounding whitespace is trimmed from both fields; a blank address is rejected. Not a `data
 * class` because the stored values are normalized copies of the constructor arguments.
 */
class MessageAddress(
    address: String,
    displayName: String? = null,
) : ToJsonObject {

    val address: String = address.trim()

    /** Blank display names become null. */
    val displayName: String? = displayName?.trim()?.takeIf { it.isNotEmpty() }

    init {
        require(this.address.isNotEmpty()) { "Message address must not be blank" }
    }

    override fun toJsonObject(): JsonObject = json {
        "address" set address
        "displayName" set displayName
    }

    override fun equals(other: Any?): Boolean =
        other is MessageAddress && other.address == address && other.displayName == displayName

    override fun hashCode(): Int = 31 * address.hashCode() + (displayName?.hashCode() ?: 0)

    /** Never prints the address itself: addresses are PII and this type ends up in logs. */
    override fun toString(): String = "MessageAddress(<redacted>)"

    companion object : FromJsonObject<MessageAddress>
}
