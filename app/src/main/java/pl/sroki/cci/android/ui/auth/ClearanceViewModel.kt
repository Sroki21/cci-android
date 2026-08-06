package pl.sroki.cci.android.ui.auth

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import pl.sroki.cci.android.data.datasource.remote.auth.ClearanceStore
import javax.inject.Inject

@HiltViewModel
class ClearanceViewModel @Inject constructor(
    private val clearanceStore: ClearanceStore,
    val baseUrl: String,
) : ViewModel() {

    /** true, gdy interceptor napotkał bramkę Cloudflare i trzeba pokazać ekran WebView. */
    val challengeRequired: StateFlow<Boolean> = clearanceStore.challengeRequired

    /** Przenosi cookies rozwiązanego challengu do OkHttpa; zwraca true, gdy jest `cf_clearance`. */
    fun onWebViewSettled(webViewUserAgent: String): Boolean =
        clearanceStore.syncFromWebView(webViewUserAgent)

    /** Zamknięcie ekranu bez rozwiązania — gasi flagę, by kolejny challenge znów otworzył WebView. */
    fun dismiss() = clearanceStore.dismissChallenge()
}
