package pl.sroki.cci.android.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Trwała flaga sterująca jednorazowym backfillem snapshotu po aktualizacji aplikacji. */
@Singleton
class VerificationPrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("collection_verification", Context.MODE_PRIVATE)

    var backfilledVersion: Int
        get() = prefs.getInt(KEY_BACKFILLED_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_BACKFILLED_VERSION, value) }

    private companion object {
        const val KEY_BACKFILLED_VERSION = "backfilled_version"
    }
}
