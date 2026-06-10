package pl.sroki.cci.android.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A fake repo for searching.
 */
object SearchRepo {
    suspend fun search(query: String): List<Cap> = withContext(Dispatchers.Default) {
        delay(200L) // simulate an I/O delay
        caps.filter { it.description?.contains(query, ignoreCase = true) ?: false }
    }
}