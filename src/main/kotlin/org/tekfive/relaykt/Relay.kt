package org.tekfive.relaykt

import org.slf4j.LoggerFactory
import org.tekfive.ack.Ack
import org.tekfive.relaykt.endpoint.Endpoint
import org.tekfive.relaykt.endpoint.EndpointResolver
import org.tekfive.relaykt.provider.Provider
import org.tekfive.relaykt.provider.ProviderConfigurations
import org.tekfive.relaykt.provider.ProviderException
import org.tekfive.relaykt.queue.MessageQueue
import org.tekfive.relaykt.queue.QueueOptions
import org.tekfive.relaykt.team.TeamMessage
import org.tekfive.relaykt.team.TeamMessagePriority
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Entry point for sending messages.
 *
 * Three delivery modes are available:
 *
 * - [send] — synchronous: the provider call happens on the calling thread and the result or
 *   failure is returned directly.
 * - [sendAsync] — asynchronous, in-process: the send runs on a virtual thread and a
 *   [CompletableFuture] is returned. Nothing is persisted; if the process dies the send is lost.
 * - [enqueue] — asynchronous, durable: the message is persisted in the KEEP-backed queue and
 *   delivered by [org.tekfive.relaykt.queue.MessageQueueProcessor] with retries, attempt history,
 *   and optional delivery receipts.
 *
 * All modes share the same validation ([validate]) and failure classification ([classify]).
 */
object Relay {

    private val log = LoggerFactory.getLogger(Relay::class.java)

    /**
     * Global default for the total attachment size allowed on one message; an
     * [Endpoint.maxAttachmentsSizeBytes] overrides it per endpoint.
     */
    val maxAttachmentsSizeBytesAck = Ack.long("MAX_ATTACHMENTS_SIZE_BYTES", 25L * 1024 * 1024, min = 0L, namespace = NAMESPACE, description = "Default maximum total size in bytes of all attachments on a single message.")

    @Volatile
    private var endpointResolver: EndpointResolver? = null

    @Volatile
    private var asyncExecutor: Executor = Executors.newVirtualThreadPerTaskExecutor()

    // ---------------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------------

    /** Registers the resolver used to look up endpoints by id (queued delivery, [send] by endpoint id). */
    fun registerEndpointResolver(resolver: EndpointResolver) {
        endpointResolver = resolver
    }

    /** Replaces the executor used by [sendAsync]. Defaults to a virtual-thread-per-task executor. */
    fun setAsyncExecutor(executor: Executor) {
        asyncExecutor = executor
    }

    fun resolveEndpoint(endpointId: String): Endpoint {
        val resolver = endpointResolver
            ?: throw IllegalStateException("No EndpointResolver registered. Call Relay.registerEndpointResolver() at startup.")
        return resolver.resolve(endpointId)
            ?: throw IllegalArgumentException("Endpoint not found for id '$endpointId'")
    }

    fun findEndpoint(endpointId: String): Endpoint? = endpointResolver?.resolve(endpointId)

    /** Clears registered configuration; intended for tests. */
    fun reset() {
        endpointResolver = null
        asyncExecutor = Executors.newVirtualThreadPerTaskExecutor()
    }

    // ---------------------------------------------------------------------------------------------
    // Synchronous
    // ---------------------------------------------------------------------------------------------

    /** Sends [message] through [endpoint] on the calling thread. */
    fun <M : Message> send(message: M, endpoint: Endpoint): SendResult {
        val provider = endpoint.providerFor<M>(message.channel)
        validate(message, provider, endpoint)
        return try {
            provider.send(message, endpoint.configuration)
        } catch (e: Exception) {
            throw classify(e, provider)
        }
    }

    /** Sends [message] through the endpoint registered under [endpointId]. */
    fun <M : Message> send(message: M, endpointId: String): SendResult = send(message, resolveEndpoint(endpointId))

    // ---------------------------------------------------------------------------------------------
    // Asynchronous (in-process)
    // ---------------------------------------------------------------------------------------------

    /**
     * Sends [message] on a background virtual thread. Validation still happens synchronously so
     * programming errors surface immediately; provider failures complete the future exceptionally
     * with a [RelayException].
     */
    fun <M : Message> sendAsync(message: M, endpoint: Endpoint): CompletableFuture<SendResult> {
        val provider = endpoint.providerFor<M>(message.channel)
        validate(message, provider, endpoint)
        return CompletableFuture.supplyAsync({
            try {
                provider.send(message, endpoint.configuration)
            } catch (e: Exception) {
                throw classify(e, provider)
            }
        }, asyncExecutor)
    }

    fun <M : Message> sendAsync(message: M, endpointId: String): CompletableFuture<SendResult> =
        sendAsync(message, resolveEndpoint(endpointId))

    // ---------------------------------------------------------------------------------------------
    // Asynchronous (durable queue)
    // ---------------------------------------------------------------------------------------------

    /**
     * Persists [message] for delivery through [endpoint] by the queue processor. Returns the queued
     * message id. Requires a KEEP database connection; see [MessageQueue].
     */
    fun enqueue(message: Message, endpoint: Endpoint, options: QueueOptions = QueueOptions()): Long {
        val provider = endpoint.providerFor<Message>(message.channel)
        validate(message, provider, endpoint)
        return MessageQueue.enqueue(message, endpoint, options)
    }

    fun enqueue(message: Message, endpointId: String, options: QueueOptions = QueueOptions()): Long =
        enqueue(message, resolveEndpoint(endpointId), options)

    // ---------------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------------

    /**
     * Looks up the delivery status of a message previously sent through [endpoint]. Returns null
     * when the provider lacks [Capability.STATUS_LOOKUP] or does not know the message.
     */
    fun status(messageId: String, endpoint: Endpoint): DeliveryStatus? {
        val provider = endpoint.provider
        if (!provider.supports(Capability.STATUS_LOOKUP) || messageId.isBlank()) {
            return null
        }
        return try {
            provider.status(messageId, endpoint.configuration)
        } catch (e: Exception) {
            throw classify(e, provider)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Validation and classification
    // ---------------------------------------------------------------------------------------------

    /**
     * Checks the endpoint configuration with the provider, then [message] against the provider's
     * capabilities and the endpoint's attachment limit. Throws [UnsupportedCapabilityException] or
     * [RelayException] (non-recoverable).
     */
    fun validate(message: Message, provider: Provider<*>, endpoint: Endpoint) {
        try {
            provider.validateConfiguration(endpoint.configuration)
        } catch (e: Exception) {
            throw RelayException("Endpoint '${endpoint.id}' configuration is invalid for provider '${provider.id}'", recoverable = false, cause = ProviderConfigurations.unwrap(e))
        }
        if (message.attachments.isNotEmpty()) {
            requireCapability(provider, Capability.ATTACHMENTS)
            val maxSizeBytes = endpoint.maxAttachmentsSizeBytes ?: maxAttachmentsSizeBytesAck()
            val totalSizeBytes = message.attachmentsSizeBytes
            if (totalSizeBytes > maxSizeBytes) {
                throw RelayException("Attachments total $totalSizeBytes bytes, exceeding the maximum of $maxSizeBytes bytes")
            }
        }
        if (message.allRecipients.size > 1) {
            requireCapability(provider, Capability.MULTIPLE_RECIPIENTS)
        }
        if (message is TeamMessage && message.priority != TeamMessagePriority.NORMAL) {
            requireCapability(provider, Capability.PRIORITY)
        }
    }

    private fun requireCapability(provider: Provider<*>, capability: Capability) {
        if (!provider.supports(capability)) {
            throw UnsupportedCapabilityException(capability, provider.id)
        }
    }

    /**
     * Normalizes any provider failure into a [RelayException] so the queue can decide whether to
     * retry. Wrapper messages are fixed, scrubbed strings; provider exception messages are only
     * reused for [ProviderException], whose contract already forbids sensitive content.
     */
    fun classify(e: Exception, provider: Provider<*>): RelayException {
        val unwrapped = ProviderConfigurations.unwrap(e)
        return when (unwrapped) {
            is RelayException -> unwrapped
            is ProviderException -> {
                val statusCode = unwrapped.statusCode
                val recoverable = statusCode != null && RelayException.isRecoverableStatus(statusCode)
                val statusText = if (statusCode != null) " with HTTP status $statusCode" else ""
                RelayException("Provider '${provider.id}' failed$statusText: ${unwrapped.message}", recoverable, e)
            }
            is IOException -> RelayException("Provider '${provider.id}' network failure", recoverable = true, cause = e)
            is IllegalStateException -> RelayException("Provider '${provider.id}' configuration is invalid", recoverable = false, cause = e)
            is IllegalArgumentException -> RelayException("Provider '${provider.id}' rejected the message as invalid", recoverable = false, cause = e)
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                RelayException("Provider '${provider.id}' send was interrupted", recoverable = true, cause = e)
            }
            else -> {
                log.debug("Unclassified provider failure from '{}'", provider.id, e)
                RelayException("Provider '${provider.id}' failed (${unwrapped.javaClass.simpleName})", recoverable = false, cause = e)
            }
        }
    }

    const val NAMESPACE = "RELAY"
}
