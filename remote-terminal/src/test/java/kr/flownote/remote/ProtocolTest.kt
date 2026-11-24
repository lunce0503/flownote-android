package kr.flownote.remote

import kr.flownote.remote.connection.*
import kr.flownote.remote.security.PinnedTrustManager
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException

class ProtocolTest {
    private val fingerprint = "AB".repeat(32)
    @Test fun credentialsNeverEnterUrl() {
        val valid = HostProfile("PC", "wss://192.168.0.2:7443/v1/connect", "a".repeat(43), fingerprint).validated()
        assertFalse(valid.endpoint.contains(valid.token))
        for (url in listOf("ws://192.168.0.2/v1/connect", "wss://user:pass@pc/v1/connect", "wss://pc/v1/connect?token=secret", "wss://pc/wrong")) {
            assertThrows(IllegalArgumentException::class.java) { valid.copy(endpoint = url).validated() }
        }
    }
    @Test fun inputChunksPreserveKoreanEmojiAndWireLimits() {
        val input = "한글😀\u0003\r".repeat(5000)
        val chunks = Protocol.inputChunks(input)
        assertEquals(input, chunks.joinToString(""))
        chunks.forEach {
            assertTrue(it.toByteArray().size <= 16 * 1024)
            assertTrue(Protocol.message("terminal.input", JSONObject().put("sessionId", "x").put("data", it)).toByteArray().size <= 64 * 1024)
            assertFalse(it.contains('\uFFFD'))
        }
    }
    @Test fun pinnedCertificateRejectsEveryOtherCertificate() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build().certificate
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val trust = PinnedTrustManager(digest)
        trust.checkServerTrusted(arrayOf(certificate), "RSA")
        val other = HeldCertificate.Builder().commonName("localhost").build().certificate
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(other), "RSA") }
    }
    @Test fun serverMessagesRejectUnsupportedVersionAndInvalidSession() {
        assertThrows(IllegalArgumentException::class.java) { Protocol.decode("""{"version":2,"type":"hello.result","payload":{}}""") }
        assertThrows(IllegalArgumentException::class.java) { Protocol.decode("""{"version":1,"type":"terminal.output","payload":{"sessionId":"not-a-uuid","seq":1,"data":"test"}}""") }
        val output = Protocol.decode("""{"version":1,"type":"terminal.output","payload":{"sessionId":"00000000-0000-4000-8000-000000000000","seq":1,"data":"안녕"}}""")
        assertEquals("안녕", output.getJSONObject("payload").getString("data"))
    }
}
