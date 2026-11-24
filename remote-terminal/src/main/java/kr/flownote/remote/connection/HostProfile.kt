package kr.flownote.remote.connection

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

data class HostProfile(val name: String, val endpoint: String, val token: String, val fingerprint: String) {
    fun validated(): HostProfile {
        require(name.isNotBlank() && name.length <= 80) { "PC 이름을 입력하세요." }
        require(endpoint.startsWith("wss://")) { "주소는 wss://로 시작해야 합니다." }
        val url = endpoint.replaceFirst("wss://", "https://").toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "주소에 인증 정보나 쿼리를 넣을 수 없습니다."
        }
        require(url.encodedPath == "/v1/connect") { "주소 경로는 /v1/connect여야 합니다." }
        require(token.matches(Regex("[A-Za-z0-9_-]{20,256}"))) { "기기 토큰을 확인하세요." }
        return copy(name = name.trim(), endpoint = url.toString().replaceFirst("https://", "wss://"), fingerprint = normalizeFingerprint(fingerprint))
    }

    fun json(): JSONObject = JSONObject().put("name", name).put("endpoint", endpoint).put("token", token).put("fingerprint", fingerprint)

    companion object {
        fun normalizeFingerprint(value: String): String {
            val normalized = value.replace(":", "").replace(" ", "").trim().uppercase()
            require(normalized.matches(Regex("[A-F0-9]{64}"))) { "SHA-256 인증서 지문 64자리를 확인하세요." }
            return normalized
        }
        fun fromJson(json: JSONObject): HostProfile = HostProfile(
            json.optString("name", "내 PC"), json.getString("endpoint"), json.getString("token"),
            json.optString("fingerprint", json.optString("certificateFingerprint")),
        ).validated()
    }
}
