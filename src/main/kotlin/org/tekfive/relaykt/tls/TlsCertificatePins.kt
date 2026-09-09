package org.tekfive.relaykt.tls

import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.GeneralSecurityException
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Validation and TLS plumbing for SHA-256 Subject Public Key Info (SPKI) certificate pins.
 *
 * Pins use the same `sha256/<base64>` representation as OkHttp. Pinning is additive to the
 * configured or platform trust store: a server certificate must both chain to a trusted CA and match at least
 * one configured pin. Multiple pins should be configured during certificate rotation.
 */
object TlsCertificatePins {

    private const val SHA256_PREFIX = "sha256/"
    private const val SHA256_BYTES = 32

    private data class TrustKey(val pins: List<String>, val caCertificate: String?)

    private val smtpSocketFactories = ConcurrentHashMap<TrustKey, SSLSocketFactory>()

    /** Trims, validates, and de-duplicates [pins] while retaining their order. */
    fun normalize(pins: List<String>): List<String> {
        return pins.map { it.trim() }.also { normalized ->
            require(normalized.none { it.isBlank() }) { "TLS certificate pins must not be blank" }
            for (pin in normalized) {
                require(pin.startsWith(SHA256_PREFIX)) {
                    "TLS certificate pins must use the sha256/<base64> format"
                }
                val decoded = try {
                    Base64.getDecoder().decode(pin.removePrefix(SHA256_PREFIX))
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("TLS certificate pin is not valid Base64", e)
                }
                require(decoded.size == SHA256_BYTES) {
                    "TLS certificate pin must contain a SHA-256 digest"
                }
            }
        }.distinct()
    }

    /** Validates [pins] and rejects pinning on a non-HTTPS URL, including loopback test URLs. */
    fun validateForUrl(url: String, pins: List<String>, description: String) {
        val normalizedPins = normalize(pins)
        if (normalizedPins.isNotEmpty()) {
            require(URI(url).scheme.equals("https", ignoreCase = true)) {
                "$description must use https when TLS certificate pins are configured"
            }
        }
    }

    /** Returns the SHA-256 SPKI pin for [certificate]. */
    fun pin(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return SHA256_PREFIX + Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Builds an SSL socket factory for Jakarta Mail. CA trust validation runs first, then at
     * least one certificate in the validated chain must match one of [pins], when pins are supplied.
     */
    fun smtpSocketFactory(pins: List<String>, caCertificate: String? = null): SSLSocketFactory {
        val normalizedPins = normalize(pins)
        val key = TrustKey(normalizedPins, caCertificate?.trim()?.takeIf { it.isNotEmpty() })
        require(normalizedPins.isNotEmpty() || key.caCertificate != null) { "Custom TLS settings are required" }
        return smtpSocketFactories.computeIfAbsent(key) {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager(normalizedPins, key.caCertificate)), null)
            context.socketFactory
        }
    }

    /** Checks the configured trust chain before applying optional public-key pins. */
    fun trustManager(pins: List<String>, caCertificate: String? = null): X509TrustManager {
        val delegate = TlsCertificates.trustManager(caCertificate)
        val normalized = normalize(pins)
        if (normalized.isEmpty()) {
            return delegate
        }
        return PinnedTrustManager(delegate, normalized.toSet())
    }

    private class PinnedTrustManager(
        private val delegate: X509TrustManager,
        private val pins: Set<String>,
    ) : X509ExtendedTrustManager() {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificates = requireChain(chain)
            delegate.checkServerTrusted(certificates, authType)
            checkPins(certificates)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val certificates = requireChain(chain)
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkServerTrusted(certificates, authType, socket)
            } else {
                delegate.checkServerTrusted(certificates, authType)
            }
            checkPins(certificates)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            val certificates = requireChain(chain)
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkServerTrusted(certificates, authType, engine)
            } else {
                delegate.checkServerTrusted(certificates, authType)
            }
            checkPins(certificates)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(requireChain(chain), authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val certificates = requireChain(chain)
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkClientTrusted(certificates, authType, socket)
            } else {
                delegate.checkClientTrusted(certificates, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            val certificates = requireChain(chain)
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkClientTrusted(certificates, authType, engine)
            } else {
                delegate.checkClientTrusted(certificates, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun requireChain(chain: Array<out X509Certificate>?): Array<X509Certificate> {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("The server did not provide a certificate chain")
            }
            return Array(chain.size) { chain[it] }
        }

        private fun checkPins(chain: Array<X509Certificate>) {
            if (pins.isEmpty()) {
                return
            }
            // Ignore unrelated certificates appended by the peer; only the validated path can satisfy a pin.
            val path = try {
                val anchors = delegate.acceptedIssuers.map { TrustAnchor(it, null) }.toSet()
                val selector = X509CertSelector().apply { certificate = chain.first() }
                val parameters = PKIXBuilderParameters(anchors, selector).apply {
                    isRevocationEnabled = false // The delegate already applied its revocation policy.
                    addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(chain.toList())))
                }
                CertPathBuilder.getInstance("PKIX").build(parameters) as PKIXCertPathBuilderResult
            } catch (e: GeneralSecurityException) {
                throw CertificateException("Unable to validate the pinned server certificate path", e)
            }
            val certificates = path.certPath.certificates.filterIsInstance<X509Certificate>() + path.trustAnchor.trustedCert
            if (certificates.none { pin(it) in pins }) {
                throw CertificateException("The server certificate chain did not match a configured TLS pin")
            }
        }
    }
}
