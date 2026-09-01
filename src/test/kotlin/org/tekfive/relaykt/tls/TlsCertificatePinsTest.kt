package org.tekfive.relaykt.tls

import okhttp3.CertificatePinner
import org.tekfive.relaykt.http.RelayHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TlsCertificatePinsTest {

    @Test
    fun `pins are normalized validated and deduplicated`() {
        assertEquals(listOf(TEST_PIN), TlsCertificatePins.normalize(listOf(" $TEST_PIN ", TEST_PIN)))
        assertFailsWith<IllegalArgumentException> { TlsCertificatePins.normalize(listOf("")) }
        assertFailsWith<IllegalArgumentException> { TlsCertificatePins.normalize(listOf("sha1/AAAAAAAAAAAAAAAAAAAAAAAAAAA=")) }
        assertFailsWith<IllegalArgumentException> { TlsCertificatePins.normalize(listOf("sha256/not-base64")) }
        assertFailsWith<IllegalArgumentException> { TlsCertificatePins.normalize(listOf("sha256/YQ==")) }
    }

    @Test
    fun `typed tls configuration round-trips through endpoint json`() {
        val original = TlsConfiguration.pinned(TEST_PIN)

        val restored = TlsConfiguration.fromJson(original.toJsonObject())

        assertEquals(original, restored)
        assertEquals(true, restored.certificatePinningEnabled)
        assertEquals(false, TlsConfiguration().certificatePinningEnabled)
    }

    @Test
    fun `http clients are cached by host and pin set`() {
        assertSame(RelayHttpClient.client, RelayHttpClient.clientFor("https://api.example.com", emptyList()))

        val first = RelayHttpClient.clientFor("https://api.example.com/v1", listOf(TEST_PIN))
        val same = RelayHttpClient.clientFor("https://api.example.com/v2", listOf(TEST_PIN))
        val otherHost = RelayHttpClient.clientFor("https://other.example.com", listOf(TEST_PIN))

        assertSame(first, same)
        assertNotSame(RelayHttpClient.client, first)
        assertNotSame(first, otherHost)
        assertEquals(false, first.followRedirects)
        assertEquals(false, first.followSslRedirects)
        assertEquals(
            CertificatePinner.Builder().add("api.example.com", TEST_PIN).build().pins,
            first.certificatePinner.pins,
        )
        assertFailsWith<IllegalArgumentException> {
            RelayHttpClient.clientFor("http://localhost:8080", listOf(TEST_PIN))
        }
    }

    companion object {
        /** A 32-byte zero digest encoded in the standard pin representation. */
        const val TEST_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
