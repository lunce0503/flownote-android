package kr.flownote.remote.connection

import org.json.JSONObject
import java.time.Instant

data class PairingInvitation(val name: String, val endpoint: String, val fingerprint: String, val code: String, val expiresAt: Instant) {
    fun checked(now: Instant = Instant.now()): PairingInvitation {
        require(expiresAt.isAfter(now) && !expiresAt.isAfter(now.plusSeconds(360))) { "등록 QR이 만료되었습니다. PC에서 새 QR을 생성하세요." }
        return this
    }
    fun profile(token: String, displayName: String = name) = HostProfile(displayName, endpoint, token, fingerprint).validated()
    companion object {
        fun parse(raw: String, now: Instant = Instant.now()): PairingInvitation {
            require(raw.length <= 8192) { "등록 QR이 너무 큽니다." }
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet() == setOf("type", "version", "name", "endpoint", "certificateFingerprint", "pairingCode", "expiresAt")) { "지원하지 않는 등록 QR입니다." }
            require(json.getString("type") == "flownote-remote-pairing" && json.get("version") == 1) { "지원하지 않는 등록 QR입니다." }
            val host = HostProfile(json.getString("name"), json.getString("endpoint"), "pairing-validation-token", json.getString("certificateFingerprint")).validated()
            val code = json.getString("pairingCode")
            require(code.matches(Regex("[A-Za-z0-9_-]{43}"))) { "등록 QR 코드를 확인하세요." }
            return PairingInvitation(host.name, host.endpoint, host.fingerprint, code, Instant.parse(json.getString("expiresAt"))).checked(now)
        }
    }
}
