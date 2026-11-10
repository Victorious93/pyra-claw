package com.victorious.pyraclaw.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.victorious.pyraclaw.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class HubSettings(
    val hubUrl: String = BuildConfig.DEFAULT_HUB_URL,
    val token: String = "",
    val paired: Boolean = false,
    val backgroundLink: Boolean = false,
    /** Optional ESP32 node for Direct mode (no Pi), e.g. "http://pyra-esp32-wake-01.local/". */
    val directUrl: String = "",
    /** The node's wake_token (can wake the PC, can't sign pulses). Keystore-encrypted at rest. */
    val directToken: String = "",
) {
    val isConfigured get() = paired && token.isNotBlank() && hubUrl.isNotBlank()
    val hasDirect get() = directUrl.isNotBlank() && directToken.isNotBlank()
}

interface SettingsStore {
    val settings: StateFlow<HubSettings>
    fun current(): HubSettings = settings.value
    fun savePairing(url: String, token: String)
    fun setBackgroundLink(enabled: Boolean)
    fun saveDirect(url: String, token: String)
    fun clearDirect()
    fun forget()
}

/** In-memory store for tests and screenshots. */
class InMemorySettingsStore(initial: HubSettings = HubSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<HubSettings> = state.asStateFlow()
    override fun savePairing(url: String, token: String) =
        state.update { it.copy(hubUrl = url, token = token, paired = true) }
    override fun setBackgroundLink(enabled: Boolean) = state.update { it.copy(backgroundLink = enabled) }
    override fun saveDirect(url: String, token: String) = state.update { it.copy(directUrl = url, directToken = token) }
    override fun clearDirect() = state.update { it.copy(directUrl = "", directToken = "") }
    override fun forget() = state.update { HubSettings() }
}

/**
 * URL and flags in SharedPreferences; the hub token encrypted with an AES-GCM key that lives in
 * the Android Keystore and never leaves it. Migrates the plaintext token that v0.3 stored.
 */
class SecureSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load(context))
    override val settings: StateFlow<HubSettings> = state.asStateFlow()

    private fun load(context: Context): HubSettings {
        migrateV03(context)
        val token = prefs.getString(K_TOKEN, null)?.let { decrypt(it) } ?: ""
        return HubSettings(
            hubUrl = prefs.getString(K_URL, null) ?: BuildConfig.DEFAULT_HUB_URL,
            token = token,
            paired = prefs.getBoolean(K_PAIRED, false) && token.isNotBlank(),
            backgroundLink = prefs.getBoolean(K_BG, false),
            directUrl = prefs.getString(K_DIRECT_URL, null) ?: "",
            directToken = prefs.getString(K_DIRECT_TOKEN, null)?.let { decrypt(it) } ?: "",
        )
    }

    private fun migrateV03(context: Context) {
        val old = context.getSharedPreferences("pyra_hub", Context.MODE_PRIVATE)
        val oldToken = old.getString("token", null) ?: return
        val oldUrl = old.getString("url", null)
        if (oldToken.isNotBlank() && encrypt(oldToken) != null) {
            prefs.edit()
                .putString(K_URL, oldUrl ?: BuildConfig.DEFAULT_HUB_URL)
                .putString(K_TOKEN, encrypt(oldToken))
                .putBoolean(K_PAIRED, true)
                .apply()
        }
        old.edit().clear().apply() // never keep the plaintext copy
    }

    override fun savePairing(url: String, token: String) {
        val enc = encrypt(token) ?: error("Android Keystore unavailable; token not saved")
        prefs.edit().putString(K_URL, url).putString(K_TOKEN, enc).putBoolean(K_PAIRED, true).apply()
        state.update { it.copy(hubUrl = url, token = token, paired = true) }
    }

    override fun setBackgroundLink(enabled: Boolean) {
        prefs.edit().putBoolean(K_BG, enabled).apply()
        state.update { it.copy(backgroundLink = enabled) }
    }

    override fun saveDirect(url: String, token: String) {
        val enc = encrypt(token) ?: error("Android Keystore unavailable; token not saved")
        prefs.edit().putString(K_DIRECT_URL, url).putString(K_DIRECT_TOKEN, enc).apply()
        state.update { it.copy(directUrl = url, directToken = token) }
    }

    override fun clearDirect() {
        prefs.edit().remove(K_DIRECT_URL).remove(K_DIRECT_TOKEN).apply()
        state.update { it.copy(directUrl = "", directToken = "") }
    }

    override fun forget() {
        prefs.edit().clear().apply()
        state.value = HubSettings()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(out, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun decrypt(blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null // key lost (e.g. restored backup): user re-pairs
    }

    private companion object {
        const val FILE = "pyra_settings"
        const val ALIAS = "pyra_claw_hub_token"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_URL = "hub_url"
        const val K_TOKEN = "hub_token_enc"
        const val K_PAIRED = "paired"
        const val K_BG = "background_link"
        const val K_DIRECT_URL = "direct_url"
        const val K_DIRECT_TOKEN = "direct_token_enc"
    }
}
