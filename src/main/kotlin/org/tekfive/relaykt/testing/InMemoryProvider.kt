package org.tekfive.relaykt.testing

import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.Capability
import org.tekfive.relaykt.Channel
import org.tekfive.relaykt.DeliveryStatus
import org.tekfive.relaykt.Message
import org.tekfive.relaykt.SendResult
import org.tekfive.relaykt.provider.Provider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Provider that records messages instead of delivering them. Registered by default for every
 * channel (`memory-email`, `memory-sms`, `memory-team`) so applications can exercise the full
 * send/queue path in tests and development without external services.
 *
 * Behaviour can be scripted per test: [failNextSendWith] makes the next send throw, and
 * [setStatus] controls what [status] reports for a message id.
 */
class InMemoryProvider(
    override val id: String,
    override val channel: Channel,
) : Provider<Message> {

    class Sent(val message: Message, val configuration: JsonObject, val result: SendResult)

    override val capabilities: Set<Capability> = setOf(
        Capability.STATUS_LOOKUP,
        Capability.ATTACHMENTS,
        Capability.PRIORITY,
        Capability.MULTIPLE_RECIPIENTS,
    )

    private val counter = AtomicLong()
    private val _sent = CopyOnWriteArrayList<Sent>()
    private val statuses = ConcurrentHashMap<String, DeliveryStatus>()

    @Volatile
    private var nextFailure: Exception? = null

    val sent: List<Sent>
        get() = _sent.toList()

    val messages: List<Message>
        get() = _sent.map { it.message }

    override fun send(message: Message, configuration: JsonObject): SendResult {
        nextFailure?.let { failure ->
            nextFailure = null
            throw failure
        }
        val result = SendResult(messageId = "$id-${counter.incrementAndGet()}", providerId = id, status = DeliveryStatus.SENT)
        _sent.add(Sent(message, configuration, result))
        return result
    }

    override fun status(messageId: String, configuration: JsonObject): DeliveryStatus? {
        if (_sent.none { it.result.messageId == messageId }) {
            return null
        }
        return statuses[messageId] ?: DeliveryStatus.SENT
    }

    fun failNextSendWith(exception: Exception) {
        nextFailure = exception
    }

    fun setStatus(messageId: String, status: DeliveryStatus) {
        statuses[messageId] = status
    }

    fun clear() {
        _sent.clear()
        statuses.clear()
        nextFailure = null
        counter.set(0)
    }

    companion object {
        val email = InMemoryProvider("memory-email", Channel.EMAIL)
        val sms = InMemoryProvider("memory-sms", Channel.SMS)
        val team = InMemoryProvider("memory-team", Channel.TEAM)

        fun clearAll() {
            email.clear()
            sms.clear()
            team.clear()
        }
    }
}
