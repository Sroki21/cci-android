package pl.sroki.cci.android.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
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
 * rozwiązywanie **stoi w miejscu** — patrz [PROG_BEZCZYNNOSCI_MS]. Instancja WebView jest przez
 * cały czas ta sama, więc pokazanie nakładki nie przeładowuje strony i nie zaczyna challengu
 * od zera.
 *
 * Próg mierzy ciszę, a nie łączny czas rozwiązywania. Cloudflare wystawia challenge **seriami**:
 * po rozwiązaniu jednego interceptor ponawia żądanie i dostaje kolejny, a każdy z nich sam w sobie
 * schodzi w 2–3 sekundy. Poprzednia wersja odmierzała sztywne 6 s od pierwszego wykrycia, więc
 * seria trzech szybkich challengów i tak przekraczała próg — bramka wyskakiwała na sekundę tuż
 * przed sukcesem, dokładnie w chwili, w której użytkownik nie miał już nic do zrobienia. Dopóki
 * strona się rusza (przekierowania challengu, [aktywnosc]) albo dopóki idzie kolejna generacja
 * challengu, odliczanie startuje od nowa. Cisza dłuższa niż próg oznacza już realne czekanie na
 * człowieka — interaktywny Turnstile do kliknięcia.
 *
 * Błąd ładowania nie pokazuje nakładki od razu — liczy się jako aktywność jak każde inne zdarzenie.
 * Managed challenge to kilka szybkich przekierowań pod rząd; pojedyncze z nich potrafi się
 * przejściowo potknąć, mimo że całość kończy się sukcesem.
 *
 * Po zdobyciu clearance flaga w `ClearanceStore` gaśnie, a nakładka znika razem z nią — stąd brak
 * osobnego `onDone`. Za zgodą administratora serwisu.
 *
 * @param challengeRequired czy bramka ma cokolwiek do roboty. `false` oznacza, że WebView wisi
 *   zamontowany na zapas (karencja w `MainActivity`) — nie pokazujemy go i nie ruszamy strony.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClearanceGate(
    challengeRequired: Boolean,
    viewModel: ClearanceViewModel = hiltViewModel(),
) {
    val generacja by viewModel.challengeGeneration.collectAsStateWithLifecycle()

    var loading by remember { mutableStateOf(true) }
    var blad by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Rośnie przy każdym zdarzeniu nawigacji WebView. Każdy przyrost odracza pokazanie bramki:
    // dopóki strona się rusza, challenge rozwiązuje się sam i nie ma czego pokazywać.
    var aktywnosc by remember { mutableIntStateOf(0) }
    var widoczna by remember { mutableStateOf(false) }

    fun trySettle(view: WebView) {
        // Po rozwiązaniu challengu WebView zostaje zamontowany na zapas — wtedy nie ma czego
        // synchronizować, a jego cookies mogłyby tylko nadpisać to, co OkHttp już ma.
        if (!viewModel.challengeRequired.value) return
        val cookies = CookieManager.getInstance().getCookie(viewModel.baseUrl) ?: return
        if (!cookies.contains("cf_clearance")) return
        // Sync gasi flagę challengu, a wraz z nią znika cała nakładka.
        viewModel.onWebViewSettled(view.settings.userAgentString)
    }

    // Zamknięcie bez rozwiązania — gasimy flagę ręcznie, żeby kolejny challenge znów otworzył bramkę.
    val zamknij = { viewModel.dismiss() }

    // Wstecz obsługujemy tylko wtedy, gdy nakładka jest na wierzchu. Niewidoczna bramka nie może
    // przejmować przycisku Wstecz aplikacji.
    BackHandler(enabled = widoczna, onBack = zamknij)

    // Każda nowa generacja to nowy challenge do rozwiązania. Przy pierwszej ładujemy stronę po
    // utworzeniu WebView, przy kolejnych przeładowujemy tę samą instancję — inaczej strona zostałaby
    // na rozwiązanym już challengu i nikt by nie wykonał skryptu następnego.
    LaunchedEffect(webView, generacja) {
        val view = webView ?: return@LaunchedEffect
        blad = null
        loading = true
        view.loadUrl(viewModel.baseUrl)
    }

    // Odliczanie ciszy: restartuje się przy każdej aktywności WebView i przy każdej nowej generacji.
    LaunchedEffect(challengeRequired, generacja, aktywnosc) {
        if (!challengeRequired) {
            if (widoczna) Log.d("CCI_CF", "bramka schowana — challenge nr $generacja rozwiazany")
            widoczna = false
            return@LaunchedEffect
        }
        delay(PROG_BEZCZYNNOSCI_MS)
        // Jedyne dwa miejsca, w których użytkownik w ogóle widzi bramkę — jeśli kiedyś znów mignie,
        // ten wpis mówi wprost, który próg ją wypuścił i po ilu zdarzeniach WebView.
        Log.d("CCI_CF", "pokazuje bramke: cisza ${PROG_BEZCZYNNOSCI_MS}ms, challenge nr $generacja, zdarzen WebView=$aktywnosc")
        widoczna = true
    }

    // Sufit na wypadek strony, która kręci przekierowania w kółko i nigdy nie milknie — wtedy sama
    // cisza nigdy by bramki nie pokazała, a użytkownik nie zobaczyłby, na czym stoi.
    LaunchedEffect(challengeRequired, generacja) {
        if (!challengeRequired) return@LaunchedEffect
        delay(SUFIT_POKAZANIA_MS)
        Log.d("CCI_CF", "pokazuje bramke: sufit ${SUFIT_POKAZANIA_MS}ms, challenge nr $generacja")
        widoczna = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Niewidoczna bramka stoi poza ekranem: layout ma pełny rozmiar (strona renderuje się
            // tak samo jak na wierzchu), ale nie zasłania UI i nie trafia w nią żadne dotknięcie.
            .offset(x = if (widoczna) 0.dp else POZA_EKRANEM),
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
                            blad = null
                            aktywnosc++
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            aktywnosc++
                            // Challenge kończy się przeładowaniem strony — po każdym
                            // zakończonym ładowaniu sprawdzamy, czy jest już clearance.
                            trySettle(view)
                        }

                        // Managed challenge potrafi ustawić cf_clearance bez pełnej nawigacji
                        // (kończy się XHR-em), a wtedy onPageFinished już nie przyjdzie
                        // i bramka wisiałaby mimo posiadanego clearance.
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            aktywnosc++
                            trySettle(view)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            // Bez tego brak sieci dawał białą stronę bez słowa wyjaśnienia. Komunikat
                            // czeka jednak w stanie — zobaczy go tylko ten, komu bramka i tak się
                            // pokaże, bo cisza po błędzie przekroczy próg.
                            if (request.isForMainFrame) {
                                loading = false
                                blad = "Nie udało się otworzyć strony. Sprawdź połączenie i spróbuj ponownie."
                                aktywnosc++
                            }
                        }
                    }
                }.also { webView = it }
            },
            // WebView z włączonym JS i DOM storage trzymał kontekst aktywności po opuszczeniu
            // ekranu, a bramka Cloudflare wraca regularnie — wyciek narastał z każdym wejściem.
            onRelease = { it.destroy() },
        )

        if (widoczna) {
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

// Ile ciszy w WebView (żadnego przekierowania, żadnego zakończonego ładowania) uznajemy za znak,
// że challenge czeka na człowieka. Managed challenge milknie najwyżej na ~3 s między krokami —
// log z urządzenia pokazywał całe rozwiązanie w 3,3 s — więc 8 s daje mu spory zapas i przy
// samodzielnym rozwiązaniu bramka nie pokazuje się ani na moment.
private const val PROG_BEZCZYNNOSCI_MS = 8_000L

// Twardy sufit dla jednej generacji challengu: strona, która bez końca się przekierowuje, nigdy
// nie zamilknie, a mimo to nic z tego nie wychodzi.
private const val SUFIT_POKAZANIA_MS = 40_000L
