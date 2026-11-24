package kr.flownote.remote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kr.flownote.remote.connection.HostProfile
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote-host-private", Context.MODE_PRIVATE)
    private val alias = "flownote-remote-profile-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun catalog(): ProfileCatalog {
        preferences.getString("catalog-v2", null)?.let { return ProfileCatalog.fromJson(JSONObject(decrypt(it))) }
        val legacy = preferences.getString("profile", null) ?: return ProfileCatalog()
        val migrated = ProfileCatalog.migrate(HostProfile.fromJson(JSONObject(decrypt(legacy))), preferences.getString("session", null))
        write(migrated)
        return migrated
    }
    private fun write(catalog: ProfileCatalog) {
        val encrypted = encrypt(catalog.json().toString())
        check(preferences.edit().putString("catalog-v2", encrypted).remove("profile").remove("session").commit())
    }
    fun save(profile: HostProfile, id: String? = null): SavedHost {
        val next = catalog().upsert(profile, id)
        write(next)
        return next.selected()!!
    }
    fun load(): HostProfile? = catalog().selected()?.profile
    fun select(id: String) { write(catalog().select(id)) }
    fun remove(id: String) { write(catalog().remove(id)) }
    fun rememberedSession(hostId: String? = catalog().selectedId): String? = catalog().sessions[hostId]
    fun rememberSession(id: String?, hostId: String? = catalog().selectedId) {
        if (hostId != null) write(catalog().rememberSession(hostId, id))
    }
    fun clear() { check(preferences.edit().clear().commit()) }
}
