package io.wrtpilot.core.network

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Self-signed router certificates are trusted by pinning their SHA-256
 * fingerprint (trust on first use, confirmed by the user).
 */
object Tls {

    private const val HEX = "0123456789abcdef"

    fun sha256(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** "ab:cd:..." grouped for display. */
    fun formatFingerprint(hex: String): String =
        hex.lowercase().chunked(2).joinToString(":")

    fun normalizeFingerprint(input: String): String =
        input.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    /** A client that accepts exactly the pinned certificate, whatever its issuer or host name. */
    fun pinnedClient(base: OkHttpClient, sha256: String): OkHttpClient {
        val tm = PinnedTrustManager(normalizeFingerprint(sha256))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Reads the router's certificate WITHOUT trusting it, so the user can be
     * shown the fingerprint before deciding. No application data is sent.
     */
    fun fetchCertificateSha256(host: String, port: Int, timeoutMs: Int = 5000): String? {
        val capture = CapturingTrustManager()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(capture), SecureRandom())
        return try {
            (ctx.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.startHandshake()
            }
            capture.seen
        } catch (e: Exception) {
            capture.seen
        }
    }
}

class PinnedTrustManager(private val expected: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
        val actual = Tls.sha256(leaf)
        if (actual != expected) throw CertificateMismatch(expected, actual)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class CertificateMismatch(val expected: String, val actual: String) :
    CertificateException("certificate fingerprint changed")

private class CapturingTrustManager : X509TrustManager {
    @Volatile var seen: String? = null

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        seen = chain.firstOrNull()?.let { Tls.sha256(it) }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        throw CertificateException("client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
