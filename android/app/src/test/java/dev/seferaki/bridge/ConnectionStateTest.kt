package dev.seferaki.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class ConnectionStateTest {
    @Test fun categorizesErrorsWithoutRevealingDetails() {
        assertEquals(ConnectionProblem.ACCESS_REVOKED, classifyConnectionError(BridgeHttpException(401)))
        assertEquals(ConnectionProblem.TRUST, classifyConnectionError(SSLHandshakeException("private detail").apply { initCause(CertificateException()) }))
        assertEquals(ConnectionProblem.UNAVAILABLE, classifyConnectionError(SocketTimeoutException("192.168.1.1")))
        assertEquals(ConnectionProblem.UNAVAILABLE, classifyConnectionError(IOException("secret token")))
        assertFalse(connectionMessage(ConnectionProblem.UNAVAILABLE).contains("secret token"))
    }
}
