package org.tekfive.relaykt.tls

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import com.sun.mail.util.SocketFetcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.tekfive.relaykt.email.smtp.SmtpConfiguration
import org.tekfive.relaykt.email.smtp.SmtpProvider
import org.tekfive.relaykt.http.RelayHttpClient
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsCaCertificateTest {
    private val material = TlsTestMaterial()

    @AfterAll
    fun close() {
        material.close()
    }

    @Test
    fun `CA trust survives server key rotation and isolates other authorities`() {
        val tls = TlsConfiguration(caCertificate = material.ca)
        for (name in listOf("server", "rotated")) {
            request(name, tls)
        }
        assertFailsWith<SSLException> { request("other", tls) }
        request("other", TlsConfiguration(caCertificate = material.ca + material.otherCa))
        assertNotSame(
            RelayHttpClient.clientFor("https://localhost", tls),
            RelayHttpClient.clientFor("https://localhost", TlsConfiguration(caCertificate = material.otherCa)),
        )
    }

    @Test
    fun `CA validation and pins must both pass`() {
        val pin = TlsCertificatePins.pin(material.certificate("server"))
        val tls = TlsConfiguration(listOf(pin), material.ca)
        request("server", tls)
        assertFailsWith<SSLException> { request("rotated", tls) }
        request("server", TlsConfiguration(listOf(pin)))
        assertFailsWith<SSLException> { request("server", TlsConfiguration(listOf(pin), material.otherCa)) }
    }

    @Test
    fun `pins alone trust self signed and private CA certificates for HTTP and SMTP`() {
        for (name in listOf("self-signed", "server")) {
            val tls = TlsConfiguration.pinned(TlsCertificatePins.pin(material.certificate(name)))
            request(name, tls)
            smtpHandshake(name, tls)
            assertFailsWith<SSLException> { request("other", tls) }
            assertFailsWith<SSLException> { smtpHandshake("other", tls) }
        }
    }

    @Test
    fun `pin only trust retains hostname and expiry checks`() {
        for (name in listOf("wrong-host", "expired")) {
            val tls = TlsConfiguration.pinned(TlsCertificatePins.pin(material.certificate(name)))
            assertFailsWith<SSLException> { request(name, tls) }
            assertFailsWith<SSLException> { smtpHandshake(name, tls) }
        }
    }

    @Test
    fun `pin only trust rejects an unrelated certificate appended to the chain`() {
        val pin = TlsCertificatePins.pin(material.certificate("server"))
        assertFailsWith<CertificateException> {
            TlsCertificatePins.trustManager(listOf(pin))
                .checkServerTrusted(arrayOf(material.certificate("other"), material.certificate("server")), "RSA")
        }
    }

    @Test
    fun `an unrelated certificate appended to a chain cannot satisfy a pin`() {
        val pin = TlsCertificatePins.pin(material.certificate("server"))
        val trust = TlsCertificatePins.trustManager(listOf(pin), material.otherCa)
        assertFailsWith<CertificateException> {
            trust.checkServerTrusted(arrayOf(material.certificate("other"), material.certificate("server")), "RSA")
        }
    }

    @Test
    fun `an omitted trusted root can satisfy a CA pin`() {
        val rootPin = TlsCertificatePins.pin(TlsCertificates.parse(material.ca).single())
        TlsCertificatePins.trustManager(listOf(rootPin), material.ca)
            .checkServerTrusted(arrayOf(material.certificate("server")), "RSA")
    }

    @Test
    fun `hostname and expiry verification remain enabled`() {
        val tls = TlsConfiguration(caCertificate = material.ca)
        assertFailsWith<SSLException> { request("wrong-host", tls) }
        assertFailsWith<SSLException> { request("expired", tls) }
    }

    @Test
    fun `CA configuration round trips and rejects malformed non-CA or plaintext inputs`() {
        val tls = TlsConfiguration(caCertificate = material.ca)
        assertEquals(tls, TlsConfiguration.fromJson(tls.toJsonObject()))
        assertFailsWith<IllegalArgumentException> { TlsConfiguration(caCertificate = "invalid") }
        assertFailsWith<IllegalArgumentException> { TlsCertificates.authorities(pem("server")) }
        assertFailsWith<IllegalArgumentException> { TlsConfiguration(caCertificate = material.ca + "garbage") }
        assertFailsWith<IllegalArgumentException> { RelayHttpClient.clientFor("http://localhost", tls) }
        assertFailsWith<IllegalArgumentException> { SmtpConfiguration("localhost", startTls = false, tls = tls) }
    }

    @Test
    fun `SMTP socket factory enforces CA pins and server identity`() {
        val pin = TlsCertificatePins.pin(material.certificate("server"))
        val tls = TlsConfiguration(listOf(pin), material.ca)
        smtpHandshake("server", tls)
        assertFailsWith<SSLException> { smtpHandshake("rotated", tls) }
        smtpHandshake("server", TlsConfiguration(listOf(pin)))
        assertFailsWith<SSLException> { smtpHandshake("wrong-host", TlsConfiguration(caCertificate = material.ca)) }
        assertFailsWith<SSLException> { smtpHandshake("expired", TlsConfiguration(caCertificate = material.ca)) }
    }

    @Test
    fun `SMTP transport trusts matching pins without a CA`() {
        for (name in listOf("self-signed", "server")) {
            smtpConnect(name, TlsConfiguration.pinned(TlsCertificatePins.pin(material.certificate(name))))
        }
    }

    @Test
    fun `SMTP transport preserves hostname errors instead of retrying with platform trust`() {
        for (mode in SmtpTlsMode.entries) {
            for (name in listOf("wrong-host", "expired")) {
                val tls = TlsConfiguration.pinned(TlsCertificatePins.pin(material.certificate(name)))
                assertFailsWith<SSLException> { smtpConnect(name, tls, mode = mode) }
            }
        }
    }

    @Test
    fun `SMTP transport cannot bypass pins through the default socket factory`() {
        val original = SSLContext.getDefault()
        val fallback = SSLContext.getInstance("TLS")
        fallback.init(null, arrayOf(TlsCertificates.trustManager(material.ca)), null)
        SSLContext.setDefault(fallback)
        try {
            // The fallback trusts this CA, but the endpoint permits only the original server key.
            val tls = TlsConfiguration(listOf(TlsCertificatePins.pin(material.certificate("server"))), material.ca)
            for (mode in SmtpTlsMode.entries) {
                assertFailsWith<SSLException> { smtpConnect("rotated", tls, mode = mode) }
            }
        } finally {
            SSLContext.setDefault(original)
        }
    }

    @Test
    fun `SMTP accepts IP SANs for implicit TLS and STARTTLS`() {
        val pin = TlsCertificatePins.pin(material.certificate("ip-server"))
        val configurations = listOf(
            TlsConfiguration(caCertificate = material.ca),
            TlsConfiguration.pinned(pin),
            TlsConfiguration(listOf(pin), material.ca),
        )
        for (mode in SmtpTlsMode.entries) {
            for (tls in configurations) {
                smtpConnect("ip-server", tls, "127.0.0.1", mode)
                assertFailsWith<SSLException> { smtpConnect("server", tls, "127.0.0.1", mode) }
            }
        }
    }

    @Test
    fun `SMTP platform trust verifies DNS and IP identities`() {
        val original = SSLContext.getDefault()
        val platform = SSLContext.getInstance("TLS")
        platform.init(null, arrayOf(TlsCertificates.trustManager(material.ca)), null)
        SSLContext.setDefault(platform)
        try {
            val tls = TlsConfiguration()
            for (mode in SmtpTlsMode.entries) {
                smtpConnect("ip-server", tls, "127.0.0.1", mode)
                smtpConnect("server", tls, mode = mode)
                assertFailsWith<SSLException> { smtpConnect("server", tls, "127.0.0.1", mode) }
                for (name in listOf("wrong-host", "expired", "other")) {
                    assertFailsWith<SSLException> { smtpConnect(name, tls, mode = mode) }
                }
            }
        } finally {
            SSLContext.setDefault(original)
        }
    }

    private enum class SmtpTlsMode { IMPLICIT, STARTTLS }

    private fun smtpConnect(
        name: String,
        tls: TlsConfiguration,
        host: String = "localhost",
        mode: SmtpTlsMode = SmtpTlsMode.IMPLICIT,
    ) {
        val configuration = SmtpConfiguration(host, sslEnabled = mode == SmtpTlsMode.IMPLICIT, tls = tls)
        val properties = SmtpProvider.buildSessionProperties(configuration)
        withServer(name) { address ->
            // Exercise Jakarta Mail's factory selection and fallback, not just the trust manager.
            when (mode) {
                SmtpTlsMode.IMPLICIT -> SocketFetcher.getSocket(host, address.port, properties, "mail.smtp", true).use { }
                SmtpTlsMode.STARTTLS -> Socket(address.address, address.port).use { plain ->
                    SocketFetcher.startTLS(plain, host, properties, "mail.smtp").use { }
                }
            }
        }
    }

    private fun pem(name: String): String {
        val encoded = java.util.Base64.getMimeEncoder().encodeToString(material.certificate(name).encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
    }

    private fun request(name: String, tls: TlsConfiguration) {
        withServer(name) { address ->
            val url = "https://localhost:${address.port}/"
            // Keep hostname verification, but avoid fallback to an address the server isn't listening on.
            val builder = OkHttpClient.Builder().dns { listOf(address.address) }
            val client = TlsHttpClient.configure(builder, url, tls).build()
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(HttpURLConnection.HTTP_OK, it.code)
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun smtpHandshake(name: String, tls: TlsConfiguration) {
        val properties = SmtpProvider.buildSessionProperties(SmtpConfiguration("localhost", tls = tls))
        assertEquals("false", properties["mail.smtp.ssl.checkserveridentity"])
        assertEquals("false", properties["mail.smtp.socketFactory.fallback"])
        val factory = properties["mail.smtp.ssl.socketFactory"] as SSLSocketFactory
        withServer(name) { address ->
            (factory.createSocket("localhost", address.port) as SSLSocket).use { socket ->
                assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
                socket.startHandshake()
            }
        }
    }

    private fun withServer(name: String, action: (InetSocketAddress) -> Unit) {
        val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(material.context(name))
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1)
            } catch (_: IOException) {
                // Negative handshake cases close before sending an HTTP request.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            action(server.address)
        } finally {
            server.stop(0)
        }
    }
}
