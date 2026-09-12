package org.tekfive.relaykt.tls

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Verify DNS and IP identities during the handshake for every SMTP socket. */
internal class SmtpSocketFactory(configuration: TlsConfiguration) : SSLSocketFactory() {
    private val delegate = if (configuration.customTrustEnabled) {
        TlsCertificatePins.smtpSocketFactory(configuration.certificatePins, configuration.caCertificate)
    } else {
        SSLContext.getDefault().socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> {
        return delegate.defaultCipherSuites
    }

    override fun getSupportedCipherSuites(): Array<String> {
        return delegate.supportedCipherSuites
    }

    override fun createSocket(): Socket {
        return verify(delegate.createSocket())
    }

    override fun createSocket(host: String, port: Int): Socket {
        return verify(delegate.createSocket(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return verify(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        return verify(delegate.createSocket(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return verify(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        return verify(delegate.createSocket(socket, host, port, autoClose))
    }

    private fun verify(socket: Socket): SSLSocket {
        val secure = socket as SSLSocket
        secure.sslParameters = secure.sslParameters.apply {
            endpointIdentificationAlgorithm = ENDPOINT_ALGORITHM
        }
        return secure
    }

    private companion object {
        const val ENDPOINT_ALGORITHM = "HTTPS"
    }
}
