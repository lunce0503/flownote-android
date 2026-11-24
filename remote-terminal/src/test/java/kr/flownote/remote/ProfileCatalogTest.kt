package kr.flownote.remote

import kr.flownote.remote.connection.HostProfile
import kr.flownote.remote.security.ProfileCatalog
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProfileCatalogTest {
    private fun host(name: String) = HostProfile(name, "wss://$name.local:7443/v1/connect", "test-token-long-enough-$name", "ab".repeat(32)).validated()
    @Test fun migrateAndRoundTripPreserveLegacyProfileAndSession() {
        val session = UUID.randomUUID().toString()
        val catalog = ProfileCatalog.migrate(host("first"), session)
        val loaded = ProfileCatalog.fromJson(catalog.json())
        assertEquals(host("first"), loaded.selected()?.profile)
        assertEquals(session, loaded.sessions[loaded.selectedId])
    }
    @Test fun selectingEditingAndDeletingKeepsOtherComputerCredentialsAndSessions() {
        var catalog = ProfileCatalog().upsert(host("first"))
        val firstId = catalog.selectedId!!
        val firstSession = UUID.randomUUID().toString()
        catalog = catalog.rememberSession(firstId, firstSession).upsert(host("second"))
        val secondId = catalog.selectedId!!
        val secondSession = UUID.randomUUID().toString()
        catalog = catalog.rememberSession(secondId, secondSession).select(firstId)
        assertEquals(host("first"), catalog.selected()?.profile)
        catalog = catalog.upsert(host("first").copy(name = "renamed"), firstId)
        assertEquals(firstSession, catalog.sessions[firstId])
        catalog = catalog.upsert(host("first").copy(token = "a-different-valid-token"), firstId)
        assertNull(catalog.sessions[firstId])
        assertEquals(secondSession, catalog.sessions[secondId])
        catalog = ProfileCatalog.fromJson(catalog.json()).remove(firstId)
        assertEquals(listOf(host("second")), catalog.hosts.map { it.profile })
        assertEquals(secondSession, catalog.sessions[secondId])
    }
    @Test fun repeatedRegistrationUpdatesOneEntryAndRejectsDuplicateEditAndLimit() {
        var catalog = ProfileCatalog().upsert(host("first"))
        val id = catalog.selectedId
        catalog = catalog.upsert(host("first").copy(name = "new name"))
        assertEquals(1, catalog.hosts.size)
        assertEquals(id, catalog.selectedId)
        catalog = catalog.upsert(host("second"))
        val current = catalog
        assertThrows(IllegalArgumentException::class.java) { current.upsert(host("first"), current.selectedId) }
        repeat(30) { catalog = catalog.upsert(host("pc$it")) }
        val full = catalog
        assertThrows(IllegalArgumentException::class.java) { full.upsert(host("overflow")) }
    }
}
