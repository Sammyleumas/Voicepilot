package com.voicepilot.ai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores small secrets (a user-supplied Gemini key, a cloud access token)
 * encrypted with an AES-256-GCM key that never leaves the Android Keystore.
 * Values are never logged and are excluded from backups.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) {

    enum class Secret(val prefKey: String) { GEMINI_API_KEY("gemini_api_key"), CLOUD_TOKEN("cloud_token") }

    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _present = MutableStateFlow(Secret.entries.associateWith { prefs.contains(it.prefKey) })

    /** Which secrets are stored (never the values). */
    val present: StateFlow<Map<Secret, Boolean>> = _present.asStateFlow()

    suspend fun get(secret: Secret): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(secret.prefKey, null) ?: return@withContext null
        runCatching { decrypt(stored) }.getOrElse {
            // Key invalidated (e.g. device restore): drop the unusable value.
            prefs.edit().remove(secret.prefKey).apply()
            refresh()
            null
        }
    }

    suspend fun put(secret: Secret, value: String) = withContext(Dispatchers.IO) {
        val clean = value.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(secret.prefKey).apply()
        } else {
            prefs.edit().putString(secret.prefKey, encrypt(clean)).apply()
        }
        refresh()
    }

    suspend fun clear(secret: Secret) = put(secret, "")

    private fun refresh() {
        _present.value = Secret.entries.associateWith { prefs.contains(it.prefKey) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (ivB64, dataB64) = stored.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "voicepilot_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
