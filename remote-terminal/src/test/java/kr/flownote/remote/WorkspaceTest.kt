package kr.flownote.remote

import kr.flownote.remote.workspace.*
import kr.flownote.remote.connection.HostProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class WorkspaceTest {
    @Test fun savedSnapshotKeepsNewerEditsAndUsesTheNewRevision() {
        val initial = CodeDocument("src/a.ts", "old", "old", "first")
        assertFalse(initial.dirty)
        val typedDuringSave = initial.copy(content = "newer").saved("submitted", "second")
        assertTrue(typedDuringSave.dirty)
        assertEquals("newer", typedDuringSave.content)
        assertEquals("submitted", typedDuringSave.base)
        assertEquals("second", typedDuringSave.revision)
        assertFalse(initial.copy(content = "submitted").saved("submitted", "second").dirty)
    }
    @Test fun workspaceUsesPinnedTlsBearerAndStrictConflictHandling() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.start()
        try {
            val pin = MessageDigest.getInstance("SHA-256").digest(cert.certificate.encoded).joinToString("") { "%02X".format(it) }
            val host = HostProfile("PC", server.url("/v1/connect").toString().replaceFirst("https", "wss"), "private-device-token-long", pin)
            val client = WorkspaceClient(host)
            server.enqueue(MockResponse().setBody("{\"version\":1,\"entries\":[],\"path\":\"\",\"rootName\":\"project\",\"nextOffset\":null}"))
            assertEquals(1, client.call("list", JSONObject().put("path", "")).getInt("version"))
            val request = server.takeRequest()
            assertEquals("/v1/workspace/list", request.path)
            assertEquals("Bearer ${host.token}", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains(host.token))
            server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
            try { client.call("write", JSONObject()); fail("Conflict ignored") } catch (error: WorkspaceFailure) { assertEquals(409, error.status) }
            try { WorkspaceClient(host.copy(fingerprint = "00".repeat(32))).call("list", JSONObject()); fail("Bad pin accepted") } catch (_: java.io.IOException) {}
        } finally { server.shutdown() }
        Unit
    }
}
