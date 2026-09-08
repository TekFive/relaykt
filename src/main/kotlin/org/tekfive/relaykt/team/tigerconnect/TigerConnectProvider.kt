package org.tekfive.relaykt.team.tigerconnect

import org.slf4j.LoggerFactory
import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.RelayException
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import org.tekfive.relaykt.provider.ProviderException
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessagePriority
import org.tekfive.relaykt.team.TeamMessageSupport
import java.io.IOException

/** Secure clinical messaging through TigerConnect, with priority and status lookup support. */
object TigerConnectProvider : Provider<TeamMessage> {

    private val log = LoggerFactory.getLogger(TigerConnectProvider::class.java)

    internal var clientFactory: (TigerConnectConfiguration) -> TigerConnectClient = { TigerConnectClient(it) }

    override val id: String = "tigerconnect"

    override val channel: Channel = Channel.TEAM

    override val capabilities: Set<Capability> = setOf(Capability.PRIORITY, Capability.STATUS_LOOKUP, Capability.MULTIPLE_RECIPIENTS)

    override fun validateConfiguration(configuration: JsonObject) {
        ProviderConfigurations.parse(TigerConnectConfiguration, configuration)
    }

    override fun send(message: TeamMessage, configuration: JsonObject): SendResult {
        val parsed = ProviderConfigurations.parse(TigerConnectConfiguration, configuration)
        val client = clientFactory(parsed)
        val resolution = TigerConnectRecipientResolver(client).resolveAll(message.to)
        if (resolution.unresolved.isNotEmpty()) {
            throw RelayException("TigerConnect could not resolve ${resolution.unresolved.size} of ${message.to.size} recipients")
        }
        if (resolution.resolved.any { it.targetType == "role" }) {
            require(!parsed.organizationId.isNullOrBlank() && !parsed.senderUserId.isNullOrBlank()) {
                "TigerConnect role messages require organizationId and senderUserId"
            }
            require(resolution.resolved.none { it.targetType == "role" && it.targetId == parsed.senderUserId }) {
                "TigerConnect role recipient must differ from the sender"
            }
        }

        val messageIds = linkedMapOf<String, String>()
        for (resolved in resolution.resolved) {
            val request = TigerConnectSendRequest(
                targetType = resolved.targetType,
                targetId = resolved.targetId,
                subject = message.subject,
                body = message.body,
                priority = encodePriority(message.priority),
            )
            try {
                val messageId = client.sendMessage(request).resolvedMessageId
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ProviderException("TigerConnect response did not include a message id")
                messageIds[resolved.recipient.address] = messageId
            } catch (e: Exception) {
                throw TeamMessageSupport.partialFailure("TigerConnect", e, sentCount = messageIds.size, totalCount = resolution.resolved.size)
            }
        }
        return SendResult(
            messageId = TeamMessageSupport.aggregateMessageIds(messageIds.values.toList()),
            providerId = id,
            status = DeliveryStatus.SENT,
            recipientMessageIds = messageIds,
        )
    }

    override fun status(messageId: String, configuration: JsonObject): DeliveryStatus? {
        val client = clientFactory(ProviderConfigurations.parse(TigerConnectConfiguration, configuration))
        val ids = TeamMessageSupport.splitMessageIds(messageId)
        val statuses = ids.map { id ->
            // Isolate per-id failures so one bad lookup does not abort the whole status check.
            try {
                val response = client.getMessageStatus(id)
                when {
                    response == null -> DeliveryStatus.UNKNOWN
                    response.isRecalled -> DeliveryStatus.FAILED
                    response.recipientStatuses.isNotEmpty() -> aggregateStatus(response.recipientStatuses.map(::mapStatus))
                    else -> mapStatus(response.status)
                }
            } catch (e: ProviderException) {
                log.warn("TigerConnect status lookup failed for 1 of {} message ids", ids.size, e)
                DeliveryStatus.UNKNOWN
            } catch (e: IOException) {
                log.warn("TigerConnect status lookup failed with I/O error for 1 of {} message ids", ids.size, e)
                DeliveryStatus.UNKNOWN
            }
        }
        return aggregateStatus(statuses)
    }

    private fun encodePriority(priority: TeamMessagePriority): String? = when (priority) {
        TeamMessagePriority.NORMAL -> null
        TeamMessagePriority.HIGH -> "high"
        TeamMessagePriority.URGENT -> "high"
    }

    internal fun mapStatus(status: String?): DeliveryStatus = when (status?.lowercase()) {
        "queued", "pending" -> DeliveryStatus.QUEUED
        "sent", "new" -> DeliveryStatus.SENT
        "delivered" -> DeliveryStatus.DELIVERED
        "read", "confirmed" -> DeliveryStatus.READ
        "failed", "error" -> DeliveryStatus.FAILED
        else -> DeliveryStatus.UNKNOWN
    }

    /**
     * Any failed delivery makes the aggregate FAILED so failures are never masked; READ and
     * DELIVERED are only reported when every recipient has reached at least that state.
     */
    internal fun aggregateStatus(statuses: List<DeliveryStatus>): DeliveryStatus = when {
        statuses.isEmpty() -> DeliveryStatus.UNKNOWN
        statuses.any { it == DeliveryStatus.FAILED } -> DeliveryStatus.FAILED
        statuses.all { it == DeliveryStatus.READ } -> DeliveryStatus.READ
        statuses.all { it == DeliveryStatus.DELIVERED || it == DeliveryStatus.READ } -> DeliveryStatus.DELIVERED
        statuses.any { it == DeliveryStatus.UNKNOWN } -> DeliveryStatus.UNKNOWN
        statuses.any { it == DeliveryStatus.QUEUED } -> DeliveryStatus.QUEUED
        else -> DeliveryStatus.SENT
    }
}
