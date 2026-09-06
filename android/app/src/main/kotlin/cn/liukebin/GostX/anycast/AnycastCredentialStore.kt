package cn.liukebin.gostx.anycast

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AnycastCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("anycast_auto", Context.MODE_PRIVATE)
    private val keyAlias = "anycast_auto_credentials_v1"

    data class Credentials(val email: String, val password: String)

    fun savedDeviceUid(): String? =
        prefs.getString("device_uid", null)?.trim()?.takeIf { it.isNotBlank() }

    fun deviceUid(): String =
        savedDeviceUid() ?: error("Device ID is not configured")

    fun saveDeviceUid(value: String) {
        require(value.isNotBlank()) { "Device ID cannot be empty" }
        prefs.edit().putString("device_uid", value.trim()).apply()
    }

    fun save(email: String, password: String) {
        prefs.edit().putString("email", encrypt(email)).putString("password", encrypt(password)).apply()
    }

    fun load(): Credentials? {
        val e = prefs.getString("email", null) ?: return null
        val p = prefs.getString("password", null) ?: return null
        return runCatching { Credentials(decrypt(e), decrypt(p)) }.getOrNull()
    }

    fun clear() = prefs.edit().remove("email").remove("password").apply()
    fun saveSelectedNode(idName: String) = prefs.edit().putString("selected_node", idName).apply()
    fun selectedNode(): String? = prefs.getString("selected_node", null)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val all = Base64.decode(value, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val data = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(data), Charsets.UTF_8)
    }
}
