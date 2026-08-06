package pl.sroki.cci.android.data.datasource.remote.firestore

import android.util.Log
import com.google.android.gms.tasks.Task
import io.sentry.Sentry

/**
 * Zgłasza trwałą porażkę zapisu do Firestore.
 *
 * Zapisy są celowo fire-and-forget: SDK kolejkuje je offline i dowozi po odzyskaniu sieci, więc
 * nie ma na co czekać. Ale zwracany [Task] był po prostu WYRZUCANY, przez co odrzucenie, którego
 * kolejka nie naprawi — reguły bezpieczeństwa, walidacja, przekroczony limit — przepadało bez
 * śladu, a rozjazd Room↔Firestore wychodził dopiero przy odtwarzaniu kolekcji.
 */
fun Task<*>.zglosBladZapisu(operacja: String) {
    addOnFailureListener { e ->
        Log.w("CCI_SYNC", "zapis do Firestore nieudany ($operacja): ${e.message}", e)
        Sentry.captureException(e)
    }
}
