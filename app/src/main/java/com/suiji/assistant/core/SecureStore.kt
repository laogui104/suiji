package com.suiji.assistant.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储：AndroidKeyStore 托管 AES-256-GCM 主密钥，密文落在独立 SharedPreferences。
 * 明文密钥不出现在磁盘、日志与备份中。
 */
object SecureStore {

    private const val PREF = "suiji_secure"
    private const val ALIAS = "suiji_master_key"
    private const val AAD = "suiji"
    private const val GCM_TAG_BITS = 128

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    fun putApiKey(ctx: Context, providerId: String, key: String) {
        if (key.isBlank()) {
            removeApiKey(ctx, providerId)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            cipher.updateAAD(AAD.toByteArray())
            val iv = cipher.iv
            val ct = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(ct, Base64.NO_WRAP)
            prefs(ctx).edit().putString("key_$providerId", blob).apply()
        }
    }

    fun getApiKey(ctx: Context, providerId: String): String? {
        val blob = prefs(ctx).getString("key_$providerId", null) ?: return null
        return runCatching {
            val parts = blob.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(AAD.toByteArray())
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    fun removeApiKey(ctx: Context, providerId: String) {
        prefs(ctx).edit().remove("key_$providerId").apply()
    }

    fun hasKey(ctx: Context, providerId: String): Boolean =
        !getApiKey(ctx, providerId).isNullOrBlank()

    /** 脱敏展示：sk-abcd...wxyz */
    fun mask(key: String?): String? {
        if (key.isNullOrBlank()) return null
        return if (key.length <= 12) "••••••"
        else key.take(6) + "..." + key.takeLast(4)
    }
}
