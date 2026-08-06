package pl.sroki.cci.android.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.ui.components.ErrorWithRetry

/**
 * Pełnoekranowa strona serwisu w [WebView], na której użytkownik ręcznie przechodzi bramkę
 * Cloudflare (i w razie potrzeby loguje się). Gdy pojawi się cookie `cf_clearance`, przenosimy je
 * wraz z UA do klienta OkHttp przez [ClearanceViewModel] i zamykamy ekran — od tej chwili żądania
 * API niosą clearance. Za zgodą administratora serwisu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClearanceScreen(
    onDone: () -> Unit,
    viewModel: ClearanceViewModel = hiltViewModel(),
) {
    var loading by remember { mutableStateOf(true) }
    // Gdy tylko cf_clearance trafi do OkHttpa, zamykamy ekran — ale tylko raz.
    var settled by remember { mutableStateOf(false) }
    var blad by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun trySettle(webView: WebView) {
        if (settled) return
        val cookies = CookieManager.getInstance().getCookie(viewModel.baseUrl) ?: return
        if (!cookies.contains("cf_clearance")) return
        // Sync gasi flagę challengu; onDone to już tylko powrót w nawigacji.
        if (viewModel.onWebViewSettled(webView.settings.userAgentString)) {
            settled = true
            onDone()
        }
    }

    // Zamknięcie bez rozwiązania — gasimy flagę ręcznie, żeby kolejny challenge znów otworzył ekran.
    val onClose = {
        viewModel.dismiss()
        onDone()
    }

    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weryfikacja Cloudflare") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Zamknij")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Cookies WebView muszą być na tyle trwałe, by dało się je odczytać po
                    // rozwiązaniu challengu i przenieść do OkHttpa.
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                // Challenge kończy się przeładowaniem strony — po każdym
                                // zakończonym ładowaniu sprawdzamy, czy jest już clearance.
                                trySettle(view)
                            }

                            // Managed challenge potrafi ustawić cf_clearance bez pełnej nawigacji
                            // (kończy się XHR-em), a wtedy onPageFinished już nie przyjdzie
                            // i ekran wisiałby mimo posiadanego clearance.
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                trySettle(view)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                // Bez tego brak sieci dawał białą stronę bez słowa wyjaśnienia.
                                if (request.isForMainFrame) {
                                    loading = false
                                    blad = "Nie udało się otworzyć strony. Sprawdź połączenie i spróbuj ponownie."
                                }
                            }
                        }
                        loadUrl(viewModel.baseUrl)
                    }.also { webView = it }
                },
                // WebView z włączonym JS i DOM storage trzymał kontekst aktywności po opuszczeniu
                // ekranu, a bramka Cloudflare wraca regularnie — wyciek narastał z każdym wejściem.
                onRelease = { it.destroy() },
            )

            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            blad?.let { komunikat ->
                ErrorWithRetry(
                    message = komunikat,
                    onRetry = {
                        blad = null
                        loading = true
                        webView?.loadUrl(viewModel.baseUrl)
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}
