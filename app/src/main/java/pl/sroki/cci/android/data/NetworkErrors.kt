package pl.sroki.cci.android.data

import retrofit2.HttpException
import java.io.IOException

/**
 * Komunikat błędu sieciowego do wyświetlenia użytkownikowi. Wcześniej ta klasyfikacja siedziała
 * w CapsView.kt (Composable) — jedyne miejsce w projekcie sprzęgnięte wprost z hierarchią
 * wyjątków Retrofit, głębiej niż wolno nawet ViewModelowi (CLAUDE.md: Composable ma zero logiki
 * biznesowej).
 */
fun networkErrorMessage(error: Throwable): String = when {
    error is IOException -> "Brak połączenia z siecią"
    error is HttpException -> "Katalog odpowiedział błędem ${error.code()}"
    !error.message.isNullOrBlank() -> error.message!!
    else -> "Nie udało się pobrać kapsli"
}
