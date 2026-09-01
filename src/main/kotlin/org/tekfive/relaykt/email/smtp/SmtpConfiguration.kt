package org.tekfive.relaykt.email.smtp

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject
import org.tekfive.relaykt.tls.TlsConfiguration

/** Endpoint configuration for [SmtpProvider]. */
data class SmtpConfiguration(
    val host: String,
    val port: Int? = null,
    val startTls: Boolean? = null,
    val sslEnabled: Boolean? = null,
    val connectionTimeoutMSecs: Int? = null,
    val timeoutMSecs: Int? = null,
    val writeTimeoutMSecs: Int? = null,
    val authenticate: Boolean = true,
    val username: String? = null,
    val password: String? = null,
    val tls: TlsConfiguration = TlsConfiguration(),
) : ToJsonObject {

    init {
        require(host.isNotBlank()) { "SMTP host is required" }
        require(!tls.certificatePinningEnabled || startTls != false || sslEnabled == true) {
            "SMTP TLS certificate pins require STARTTLS or SSL"
        }
    }

    val shouldAuthenticate: Boolean
        get() = authenticate && !username.isNullOrBlank() && !password.isNullOrBlank()

    override fun toString(): String {
        return "SmtpConfiguration(host=$host, port=$port, startTls=$startTls, sslEnabled=$sslEnabled, " +
            "connectionTimeoutMSecs=$connectionTimeoutMSecs, timeoutMSecs=$timeoutMSecs, " +
            "writeTimeoutMSecs=$writeTimeoutMSecs, authenticate=$authenticate, username=$username, " +
            "password=REDACTED, tls=$tls)"
    }

    companion object : FromJsonObject<SmtpConfiguration>
}
