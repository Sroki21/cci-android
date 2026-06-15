package pl.sroki.cci.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.sroki.cci.android.data.FirebaseAuthManager
import pl.sroki.cci.android.data.FirestoreRestoreUseCase
import javax.inject.Inject

@HiltAndroidApp
class CCIApplication : Application() {

    @Inject lateinit var firebaseAuthManager: FirebaseAuthManager
    @Inject lateinit var firestoreRestoreUseCase: FirestoreRestoreUseCase

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        SentryAndroid.init(this) { options -> options.isDebug = BuildConfig.DEBUG }
        super.onCreate()
        applicationScope.launch {
            try {
                firebaseAuthManager.ensureSignedIn()
                firebaseAuthManager.uid.value?.let { uid ->
                    Sentry.setUser(User().apply { id = uid })
                }
                firestoreRestoreUseCase.deduplicateRoomData()
                firestoreRestoreUseCase.restoreIfEmpty()
            } catch (e: Exception) {
                Sentry.captureException(e)
                // Restore failure is non-fatal — app works offline without it
            }
        }
    }
}
