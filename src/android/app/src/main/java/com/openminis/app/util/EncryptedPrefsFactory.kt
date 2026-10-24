package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` (wrapped as
 * `GeneralSecurityException`) on launch when the AndroidKeystore master
 * key can no longer decrypt the Tink keyset blob — observed on Samsung
 * One UI / Android 16 after backup-restore or biometric re-enroll. The
 * exception bubbles to the main thread and the app dies in a relaunch
 * loop because every cold start hits the same lazy init.
 *
 * Strategy:
 *  1. Try the normal create.
 *  2. On any crypto error: drop the encrypted XML file + the on-disk
 *     Tink keyset prefs file + the AndroidKeystore alias, then retry
 *     once. The user loses stored credentials (they need to re-paste
 *     their API key / re-login OAuth) but the app boots.
 *  3. If recreate still fails: fall back to a PLAIN-TEXT
 *     SharedPreferences so the rest of the app sees an empty,
 *     read-write store and never crashes. Plain-text fallback is a
 *     last-resort safety net — the on-disk file is named with a
 *     "_plain_fallback" suffix so it's distinguishable from real
 *     encrypted state and never gets promoted back to the encrypted
 *     slot on the next launch.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"

    // [38.9.11.3.1] one open store per file for the life of the process. Opening one decrypts its Tink keysets through
    // the Android Keystore (seconds on some phones) under Tink's single global lock, and callers like
    // ProviderRepository.hasAnyCredential / OAuthManager.needsReauth used to re-open it on every check — on the main
    // thread too, which waited behind another thread's open and hit an ANR (realme RMX5062, 2026-10-21).
    private val cache = com.openminis.app.plus.OnceCache<SharedPreferences>()

    /** [38.9.11.3.1] the stores the chat screen / provider routing read; opened in the background at app start */
    val UI_STORES = arrayOf("oauth_prefs", "provider_secrets", "mines_gemini_accounts")

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        cache.peek(fileName)?.let { return it }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            Log.w(TAG, "first open of $fileName on the main thread (Keystore; may block)")
        return cache.get(fileName) { createUncached(context.applicationContext ?: context, fileName) }
    }

    /** [38.9.11.3.1] true once every named store is open (a read is then a map lookup, no Keystore, no Tink lock) */
    fun isOpen(vararg fileNames: String): Boolean = fileNames.all { cache.peek(it) != null }

    /** [38.9.11.3.1] open the stores the UI checks most, on a background thread, so the first check finds them open. */
    fun prewarm(context: Context, vararg fileNames: String) {
        val app = context.applicationContext ?: context
        Thread({ fileNames.forEach { n -> runCatching { safeCreate(app, n) } } }, "mines-prefs-warm")
            .apply { isDaemon = true }.start()
    }

    private fun createUncached(context: Context, fileName: String): SharedPreferences {
        runCatching { return build(context, fileName) }
            .onFailure { Log.w(TAG, "first create($fileName) failed: ${it.message}") }

        // First wipe attempt — the encrypted XML + Tink keyset blob +
        // master-key alias all need to go. The Tink keyset lives in its
        // own __androidx_security_crypto_encrypted_prefs__ file keyed
        // by the SP file name; drop both so create() regenerates them.
        wipeEncryptedState(context, fileName)

        runCatching { return build(context, fileName) }
            .onFailure {
                Log.e(TAG, "rebuild($fileName) after wipe failed: ${it.message}", it)
            }

        Log.w(TAG, "falling back to plain SharedPreferences for $fileName — credentials lost")
        return context.getSharedPreferences("${fileName}_plain_fallback", Context.MODE_PRIVATE)
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun wipeEncryptedState(context: Context, fileName: String) {
        // XML file the SP itself reads/writes.
        runCatching {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "$fileName.xml").delete()
            // Tink keyset blob is stashed in this companion prefs file.
            File(dir, "__androidx_security_crypto_encrypted_prefs__.xml").delete()
        }.onFailure { Log.w(TAG, "wipe prefs files failed: ${it.message}") }

        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }.onFailure { Log.w(TAG, "wipe master-key alias failed: ${it.message}") }
    }
}
