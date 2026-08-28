package org.tekfive.relaykt

import org.tekfive.relaykt.provider.ProviderRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProviderRegistryTest {

    @AfterTest
    fun tearDown() = ProviderRegistry.reset()

    @Test
    fun `built-in providers are registered for every channel`() {
        assertEquals(setOf("smtp", "sendgrid", "zeptomail", "memory-email"), ProviderRegistry.forChannel(Channel.EMAIL).map { it.id }.toSet())
        assertEquals(setOf("twilio-sms", "memory-sms"), ProviderRegistry.forChannel(Channel.SMS).map { it.id }.toSet())
        assertEquals(setOf("slack", "tigerconnect", "msteams", "memory-team"), ProviderRegistry.forChannel(Channel.TEAM).map { it.id }.toSet())
    }

    @Test
    fun `providers can be added, replaced, and removed`() {
        val custom = object : org.tekfive.relaykt.provider.Provider<Message> {
            override val id = "custom"
            override val channel = Channel.SMS
            override val capabilities = emptySet<Capability>()
            override fun send(message: Message, configuration: org.tekfive.jfk.JsonObject) = SendResult("1", id)
        }
        ProviderRegistry.register(custom)
        assertNotNull(ProviderRegistry.find("custom"))
        assertFailsWith<IllegalStateException> { ProviderRegistry.get<Message>("custom", Channel.EMAIL) }
        ProviderRegistry.unregister("custom")
        assertNull(ProviderRegistry.find("custom"))
        assertFailsWith<IllegalArgumentException> { ProviderRegistry.get("custom") }
    }
}
