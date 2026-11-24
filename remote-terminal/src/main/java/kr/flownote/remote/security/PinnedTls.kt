package kr.flownote.remote.security

import kr.flownote.remote.connection.HostProfile
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class PinnedTrustManager(fingerprint: String) : X509TrustManager {
    private val expected = HostProfile.normalizeFingerprint(fingerprint).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client certificates not supported")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing host certificate")
        leaf.checkValidity()
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(leaf.encoded))) {
            throw CertificateException("Host certificate fingerprint mismatch")
        }
    }
}

fun pinnedClient(profile: HostProfile): OkHttpClient {
    val trust = PinnedTrustManager(profile.fingerprint)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trust)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}
