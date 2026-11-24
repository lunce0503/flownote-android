package kr.flownote.remote.security

import kr.flownote.remote.connection.HostProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedHost(val id: String, val profile: HostProfile)

data class ProfileCatalog(
    val hosts: List<SavedHost> = emptyList(),
    val selectedId: String? = null,
    val sessions: Map<String, String> = emptyMap(),
) {
    fun selected(): SavedHost? = hosts.find { it.id == selectedId }
    fun upsert(profile: HostProfile, id: String? = null): ProfileCatalog {
        val validated = profile.validated()
        val existing = if (id == null) hosts.find { it.profile.endpoint == validated.endpoint } else hosts.find { it.id == id }
        require(id == null || existing != null) { "저장된 PC를 찾을 수 없습니다." }
        require(hosts.none { it.id != existing?.id && it.profile.endpoint == validated.endpoint }) { "같은 주소의 PC가 이미 저장되어 있습니다." }
        require(existing != null || hosts.size < 32) { "PC는 최대 32대까지 저장할 수 있습니다." }
        val saved = SavedHost(existing?.id ?: UUID.randomUUID().toString(), validated)
        val sameCredentials = existing?.profile?.let { it.endpoint == validated.endpoint && it.token == validated.token && it.fingerprint == validated.fingerprint } == true
        val next = if (existing == null) hosts + saved else hosts.map { if (it.id == saved.id) saved else it }
        return copy(hosts = next, selectedId = saved.id, sessions = if (sameCredentials) sessions else sessions - saved.id)
    }
    fun remove(id: String): ProfileCatalog = copy(
        hosts = hosts.filterNot { it.id == id },
        selectedId = if (selectedId == id) hosts.firstOrNull { it.id != id }?.id else selectedId,
        sessions = sessions - id,
    )
    fun select(id: String): ProfileCatalog {
        require(hosts.any { it.id == id }) { "저장된 PC를 찾을 수 없습니다." }
        return copy(selectedId = id)
    }
    fun rememberSession(id: String, session: String?): ProfileCatalog {
        require(hosts.any { it.id == id }) { "저장된 PC를 찾을 수 없습니다." }
        if (session != null) UUID.fromString(session)
        return copy(sessions = if (session == null) sessions - id else sessions + (id to session))
    }
    fun json(): JSONObject = JSONObject().put("version", 2).put("hosts", JSONArray().apply {
        hosts.forEach { put(JSONObject().put("id", it.id).put("profile", it.profile.json())) }
    }).put("selectedId", selectedId ?: JSONObject.NULL).put("sessions", JSONObject(sessions))

    companion object {
        fun fromJson(json: JSONObject): ProfileCatalog {
            require(json.getInt("version") == 2)
            val values = json.getJSONArray("hosts")
            require(values.length() <= 32)
            val hosts = (0 until values.length()).map {
                val host = values.getJSONObject(it)
                SavedHost(UUID.fromString(host.getString("id")).toString(), HostProfile.fromJson(host.getJSONObject("profile")))
            }
            require(hosts.map { it.id }.distinct().size == hosts.size)
            require(hosts.map { it.profile.endpoint }.distinct().size == hosts.size)
            val selectedId = json.optString("selectedId").takeIf { id -> hosts.any { it.id == id } }
            val sessions = json.getJSONObject("sessions")
            val remembered = hosts.mapNotNull { host ->
                sessions.optString(host.id).takeIf { it.isNotBlank() }?.let { host.id to UUID.fromString(it).toString() }
            }.toMap()
            return ProfileCatalog(hosts, selectedId, remembered)
        }
        fun migrate(profile: HostProfile, session: String?): ProfileCatalog {
            val catalog = ProfileCatalog().upsert(profile)
            return if (session == null) catalog else catalog.rememberSession(catalog.selectedId!!, session)
        }
    }
}
