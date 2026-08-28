package org.tekfive.relaykt

/**
 * Optional features a [org.tekfive.relaykt.provider.Provider] may support. [org.tekfive.relaykt.Relay]
 * validates a message against the provider's capabilities before sending so unsupported requests
 * fail fast instead of being silently dropped by the provider.
 */
enum class Capability(val displayName: String) {
    /** The provider can report delivery status for a previously sent message id. */
    STATUS_LOOKUP("Status Lookup"),

    /** The provider accepts file attachments. */
    ATTACHMENTS("Attachments"),

    /** The provider honours a message priority (team messaging). */
    PRIORITY("Priority"),

    /** The provider can deliver one message to more than one recipient in a single send. */
    MULTIPLE_RECIPIENTS("Multiple Recipients"),
}
