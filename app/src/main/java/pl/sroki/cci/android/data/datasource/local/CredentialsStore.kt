package pl.sroki.cci.android.data.datasource.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Poświadczenia potrzebne do cichego odnowienia wygasłej sesji webowej (patrz SessionRefresher).
 *
 * Hasło jest szyfrowane AES-GCM kluczem wygenerowanym w Android Keystore — klucz nie opuszcza
 * bezpiecznego magazynu systemu i nie da się go wyeksportować, więc w SharedPreferences ląduje
 * wyłącznie szyfrogram. Klucz bywa unieważniany przez system (przywrócenie kopii zapasowej na
 * inne urządzenie, zmiana zabezpieczeń ekranu blokady) — wtedy odszyfrowanie rzuca wyjątkiem,
 * co traktujemy jak brak poświadczeń i czyścimy magazyn.
 */
@Singleton
class CredentialsStore @Inject constructor(@ApplicationContext context: Context) {

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "cci_credentials_key"
        const val PREFS_NAME = "credentials"
        const val PREF_EMAIL = "email"
        const val PREF_PASSWORD = "password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_BYTES = 12
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(email: String, password: String) {
        runCatching {
            prefs.edit()
                .putString(PREF_EMAIL, encrypt(email))
                .putString(PREF_PASSWORD, encrypt(password))
                .apply()
        }.onFailure {
            Log.w("CCI_AUTH", "credentials store: zapis nieudany: ${it.message}")
            clear()
        }
    }

    fun load(): Credentials? {
        val email = prefs.getString(PREF_EMAIL, null) ?: return null
        val password = prefs.getString(PREF_PASSWORD, null) ?: return null
        return runCatching { Credentials(decrypt(email), decrypt(password)) }
            .onFailure {
                // Klucz unieważniony lub dane uszkodzone — szyfrogram jest bezużyteczny.
                Log.w("CCI_AUTH", "credentials store: odczyt nieudany: ${it.message}")
                clear()
            }
            .getOrNull()
    }

    fun hasCredentials(): Boolean = prefs.contains(PREF_PASSWORD)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    // IV jest losowe per zapis i doklejone na początku szyfrogramu — GCM wymaga unikalnego IV
    // dla każdego szyfrowania tym samym kluczem.
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, payload, 0, IV_BYTES)
        )
        return String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
    }

    data class Credentials(val email: String, val password: String)
}
