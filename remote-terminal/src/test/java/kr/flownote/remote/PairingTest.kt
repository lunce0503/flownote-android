package kr.flownote.remote

import kr.flownote.remote.connection.PairingInvitation
import kr.flownote.remote.connection.PairingClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.security.MessageDigest

class PairingTest {
    private fun payload(endpoint: String = "wss://pc.local:7443/v1/connect", fingerprint: String = "ab".repeat(32), expires: Instant = Instant.now().plusSeconds(300)) = JSONObject()
        .put("type", "flownote-remote-pairing").put("version", 1).put("name", "Work PC").put("endpoint", endpoint)
        .put("certificateFingerprint", fingerprint).put("pairingCode", "a".repeat(43)).put("expiresAt", expires.toString())
    @Test fun rejectsExpiredForeignOversizedAndUnsafeDestinationQr() {
        val now = Instant.now()
        assertEquals("Work PC", PairingInvitation.parse(payload().toString()).name)
        assertThrows(IllegalArgumentException::class.java) { PairingInvitation.parse(payload(expires = now.minusSeconds(1)).toString(), now) }
        assertThrows(IllegalArgumentException::class.java) { PairingInvitation.parse(payload(expires = now.plusSeconds(400)).toString(), now) }
        assertThrows(IllegalArgumentException::class.java) { PairingInvitation.parse(payload(endpoint = "https://pc.local/v1/connect").toString()) }
        assertThrows(IllegalArgumentException::class.java) { PairingInvitation.parse(payload(endpoint = "wss://pc.local/v1/connect?token=secret").toString()) }
        assertThrows(IllegalArgumentException::class.java) { PairingInvitation.parse(payload().put("version", 99).toString()) }
        assertThrows(IllegalArgumentException::class.java) { PairingInvitation.parse("x".repeat(8193)) }
    }
    @Test fun tlsPairingExchangesOnlyOneTimeCodeAndPinsTheCertificate() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false)
        server.start()
        try {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02X".format(it) }
            val invitation = PairingInvitation.parse(payload(server.url("/v1/connect").toString().replaceFirst("https", "wss"), fingerprint).toString())
            try { PairingClient().redeem(invitation, "x".repeat(81)); fail("Invalid name consumed a code") } catch (_: IllegalArgumentException) {}
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody(JSONObject().put("version", 1).put("deviceId", "phone-test").put("token", "issued-token-long-enough").toString()))
            val profile = PairingClient().redeem(invitation, "My PC")
            assertEquals("My PC", profile.name)
            assertEquals("issued-token-long-enough", profile.token)
            val request = server.takeRequest()
            assertEquals("/v1/pair", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(invitation.code, JSONObject(request.body.readUtf8()).getString("pairingCode"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            try { PairingClient().redeem(invitation, "My PC"); fail("Reused code accepted") } catch (_: IllegalArgumentException) {}
            try { PairingClient().redeem(invitation.copy(fingerprint = "00".repeat(32)), "My PC"); fail("Bad pin accepted") } catch (_: java.io.IOException) {}
        } finally { server.shutdown() }
        Unit
    }
}
