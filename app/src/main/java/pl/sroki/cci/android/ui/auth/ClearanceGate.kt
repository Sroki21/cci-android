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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import pl.sroki.cci.android.ui.components.ErrorWithRetry

/**
 * Bramka Cloudflare jako nakładka nad aplikacją — nie osobny ekran w nawigacji.
 *
 * Managed challenge przechodzi sam: [WebView] wykonuje JavaScript, dostaje cookie `cf_clearance`
 * i po chwili jest po sprawie. Użytkownik nie ma tam w co kliknąć, a mimo to dostawał na wierzch
 * pełny ekran „Weryfikacja Cloudflare", który zaraz znikał — samo mignięcie w środku zwykłego
 * wejścia na listę kapsli.
 *
 * Dlatego bramka startuje **poza ekranem**: WebView jest w kompozycji, ładuje stronę i wykonuje
 * skrypt challengu, ale nic nie zasłania i nie łapie dotknięć. Na wierzch wjeżdża dopiero, gdy
 * [visible] zrobi się `true` — czyli gdy rozwiązywanie przeciąga się ponad próg (interaktywny
 * Turnstile do kliknięcia) albo gdy [onNeedsUser] zgłosi błąd ładowania. Instancja WebView jest
 * przez cały czas ta sama, więc pokazanie nakładki nie przeładowuje strony i nie zaczyna
 * challengu od zera.
 *
 * Po zdobyciu clearance flaga w `ClearanceStore` gaśnie, a nakładka znika razem z nią — stąd brak
 * osobnego `onDone`. Za zgodą administratora serwisu.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClearanceGate(
    visible: Boolean,
    onClose: () -> Unit,
    onNeedsUser: () -> Unit,
    viewModel: ClearanceViewModel = hiltViewModel(),
) {
    var loading by remember { mutableStateOf(true) }
    // Gdy tylko cf_clearance trafi do OkHttpa, gasimy flagę — ale tylko raz.
    var settled by remember { mutableStateOf(false) }
    var blad by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // WebViewClient powstaje raz, w factory, więc musi sięgać po aktualną wersję callbacku.
    val zglosPotrzebeUzytkownika by rememberUpdatedState(onNeedsUser)

    // Błąd ładowania w tle nie może czekać na timeout interceptora (2 minuty ciszy) — jak nie ma
    // sieci, użytkownik ma to zobaczyć od razu.
    LaunchedEffect(blad) {
        if (blad != null) zglosPotrzebeUzytkownika()
    }

    fun trySettle(view: WebView) {
        if (settled) return
        val cookies = CookieManager.getInstance().getCookie(viewModel.baseUrl) ?: return
        if (!cookies.contains("cf_clearance")) return
        // Sync gasi flagę challengu, a wraz z nią znika cała nakładka.
        if (viewModel.onWebViewSettled(view.settings.userAgentString)) {
            settled = true
        }
    }

    // Zamknięcie bez rozwiązania — gasimy flagę ręcznie, żeby kolejny challenge znów otworzył bramkę.
    val zamknij = {
        viewModel.dismiss()
        onClose()
    }

    // Wstecz obsługujemy tylko wtedy, gdy nakładka jest na wierzchu. Niewidoczna bramka nie może
    // przejmować przycisku Wstecz aplikacji.
    BackHandler(enabled = visible, onBack = zamknij)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Niewidoczna bramka stoi poza ekranem: layout ma pełny rozmiar (strona renderuje się
            // tak samo jak na wierzchu), ale nie zasłania UI i nie trafia w nią żadne dotknięcie.
            .offset(x = if (visible) 0.dp else POZA_EKRANEM),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // Stały odstęp na pasek nakładki — dzięki temu pokazanie bramki nie zmienia
                // rozmiaru WebView i nie wywołuje przeładowania układu strony w połowie challengu.
                .padding(top = WYSOKOSC_PASKA),
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
                        // i bramka wisiałaby mimo posiadanego clearance.
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

        if (visible) {
            Column(Modifier.fillMaxWidth().safeDrawingPadding()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = zamknij) {
                            Icon(Icons.Filled.Close, contentDescription = "Zamknij")
                        }
                        Text(
                            text = "Weryfikacja Cloudflare",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
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

// Przesunięcie wystarczająco duże, by nakładka wyjechała poza każdy realny ekran.
private val POZA_EKRANEM = 4000.dp

// Wysokość paska nakładki (przycisk zamknięcia + tytuł) — tyle miejsca zostawiamy na górze.
private val WYSOKOSC_PASKA = 56.dp
