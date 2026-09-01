package org.tekfive.relaykt.tls

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

/**
 * Strongly typed TLS settings shared by every external RelayKt provider.
 *
 * [certificatePins] are SHA-256 Subject Public Key Info pins in `sha256/<base64>` form. An empty
 * list keeps the platform's normal CA and hostname validation without adding certificate pinning.
 */
data class TlsConfiguration(
    val certificatePins: List<String> = emptyList(),
) : ToJsonObject {

    init {
        TlsCertificatePins.normalize(certificatePins)
    }

    val certificatePinningEnabled: Boolean
        get() = certificatePins.isNotEmpty()

    internal fun validateForUrl(url: String, description: String) {
        TlsCertificatePins.validateForUrl(url, certificatePins, description)
    }

    companion object : FromJsonObject<TlsConfiguration> {
        /** Convenience factory for a pinned TLS configuration. */
        fun pinned(vararg certificatePins: String): TlsConfiguration =
            TlsConfiguration(certificatePins.toList())
    }
}
