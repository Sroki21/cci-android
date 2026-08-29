package pl.sroki.cci.android.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Zasięg żyjący tak długo jak proces aplikacji — dla operacji, których nie wolno przerwać
 * wyjściem z ekranu.
 *
 * `viewModelScope` ginie razem z ViewModelem, a Retrofit anuluje wtedy trwający `Call`. Przy
 * żywej sesji zapis do kolekcji trwa ~130 ms i nikt nie zdąży wyjść, ale gdy sesja webowa
 * wygasła, `ReauthInterceptor` przechodzi całą ścieżkę odzyskiwania (401 → odświeżenie CSRF →
 * 401 → ciche logowanie → ponowienie) i zajmuje to około dwóch sekund. Cofnięcie się z ekranu
 * w tym oknie ubijało żądanie **pomiędzy udanym logowaniem a ponowieniem**: log pokazywał
 * `reauth: … -> SUCCESS`, a POST-a już nie było i serwer nic nie zapisywał.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    // SupervisorJob: porażka jednej operacji nie może unieważnić zasięgu dla kolejnych.
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
