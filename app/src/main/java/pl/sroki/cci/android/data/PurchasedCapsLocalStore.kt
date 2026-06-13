package pl.sroki.cci.android.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PurchasedCapsLocalStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("purchased_caps", Context.MODE_PRIVATE)

    fun add(capId: Long) {
        val ids = rawIds().toMutableSet()
        ids.add(capId.toString())
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    fun remove(capId: Long) {
        val ids = rawIds().toMutableSet()
        ids.remove(capId.toString())
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    fun getIds(): Set<Long> = rawIds().mapNotNull { it.toLongOrNull() }.toSet()

    private fun rawIds(): Set<String> = prefs.getStringSet(KEY, null) ?: emptySet()

    companion object {
        private const val KEY = "ids"
    }
}
