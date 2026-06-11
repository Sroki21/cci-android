package pl.sroki.cci.android.data.datasource.remote.auth

import com.franmontiel.persistentcookiejar.PersistentCookieJar
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import pl.sroki.cci.android.data.SessionRepository

class SessionAuthenticator(
    private val cookieJar: PersistentCookieJar,
    private val sessionRepository: SessionRepository
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        cookieJar.clear()
        sessionRepository.setLoggedIn(false)
        return null
    }
}
