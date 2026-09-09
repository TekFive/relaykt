package org.tekfive.relaykt.tls

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

/**
 * Strongly typed TLS settings shared by every external RelayKt provider.
 *
 * [certificatePins] are SHA-256 Subject Public Key Info pins in `sha256/<base64>` form. An empty
 * list adds no pin restriction. [caCertificate] optionally replaces platform trust with a PEM CA bundle.
 */
data class TlsConfiguration(
    val certificatePins: List<String> = emptyList(),
    val caCertificate: String? = null,
) : ToJsonObject {

    init {
        TlsCertificatePins.normalize(certificatePins)
        caCertificate?.takeIf { it.isNotBlank() }?.let { TlsCertificates.authorities(it) }
    }

    val certificatePinningEnabled: Boolean
        get() = certificatePins.isNotEmpty()

    val customTrustEnabled: Boolean
        get() = certificatePinningEnabled || !caCertificate.isNullOrBlank()

    internal fun validateForUrl(url: String, description: String) {
        TlsCertificatePins.validateForUrl(url, certificatePins, description)
        require(!customTrustEnabled || java.net.URI(url).scheme.equals("https", ignoreCase = true)) {
            "$description must use https when custom TLS settings are configured"
        }
    }

    override fun toString(): String {
        return "TlsConfiguration(certificatePins=$certificatePins, customCa=${!caCertificate.isNullOrBlank()})"
    }

    companion object : FromJsonObject<TlsConfiguration> {
        /** Convenience factory for a pinned TLS configuration. */
        fun pinned(vararg certificatePins: String): TlsConfiguration =
            TlsConfiguration(certificatePins.toList())
    }
}
