package org.tekfive.relaykt.tls

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Parses PEM certificates and builds service-local trust without changing JVM defaults. */
object TlsCertificates {
    private val pemBlock = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
    private const val KEY_CERT_SIGN = 5

    fun parse(pem: String): List<X509Certificate> {
        require(pemBlock.containsMatchIn(pem) && pemBlock.replace(pem, "").isBlank()) {
            "Expected PEM certificate blocks only"
        }
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            pemBlock.findAll(pem).map { block ->
                factory.generateCertificate(ByteArrayInputStream(block.value.toByteArray())) as X509Certificate
            }.toList()
        } catch (e: CertificateException) {
            throw IllegalArgumentException("Invalid PEM certificate", e)
        }
    }

    fun authorities(pem: String): List<X509Certificate> {
        val certificates = parse(pem)
        for (certificate in certificates) {
            require(certificate.basicConstraints >= 0) { "CA certificates must have CA:TRUE" }
            val usage = certificate.keyUsage
            require(usage == null || usage.getOrElse(KEY_CERT_SIGN) { false }) {
                "CA certificates must permit certificate signing"
            }
            try {
                certificate.checkValidity()
            } catch (e: CertificateException) {
                throw IllegalArgumentException("CA certificate is expired or not yet valid", e)
            }
        }
        return certificates
    }

    /** Null uses platform trust; a PEM bundle replaces it for this client only. */
    fun trustManager(caCertificate: String? = null): X509TrustManager {
        val pem = caCertificate?.takeIf { it.isNotBlank() }
        val store = if (pem == null) {
            null
        } else {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                authorities(pem).forEachIndexed { index, certificate ->
                    setCertificateEntry("ca-$index", certificate)
                }
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
