package org.tekfive.relaykt

/**
 * Provider-agnostic delivery status. Providers map their own vocabularies onto this enum; the
 * ordering runs from "least progressed" to "most progressed", with the two failure states last.
 */
enum class DeliveryStatus {
    /** Accepted by the provider but not yet handed off. */
    QUEUED,

    /** Handed off to the downstream network/recipient system. */
    SENT,

    /** Confirmed delivered to the recipient endpoint. */
    DELIVERED,

    /** Recipient opened the message (email open tracking). */
    OPENED,

    /** Recipient read the message (team messaging read receipts). */
    READ,

    /** The provider reported that delivery failed. */
    FAILED,

    /** The provider cannot say, or does not support status lookup. */
    UNKNOWN,
    ;

    /** Whether this status confirms the message reached the recipient. */
    val isReceived: Boolean
        get() = this == DELIVERED || this == OPENED || this == READ

    val isTerminal: Boolean
        get() = isReceived || this == FAILED
}
