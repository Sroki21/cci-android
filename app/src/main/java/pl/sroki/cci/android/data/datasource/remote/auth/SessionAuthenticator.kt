package pl.sroki.cci.android.data.datasource.remote.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import pl.sroki.cci.android.data.SessionRepository

class SessionAuthenticator(
    private val sessionRepository: SessionRepository
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Aplikacja uwierzytelnia się dwutorowo: api/v1/* Bearer tokenem, data/* sesją webową.
        // 401 z data/* znaczy tylko tyle, że wygasła sesja — token bywa wtedy dalej ważny.
        // Kasowanie stanu usunęłoby też cookie "remember", z którego backend odtwarza sesję,
        // więc jedno nieudane żądanie wylogowywało z aplikacji na stałe.
        if (!response.request.url.encodedPath.startsWith("/api/")) return null

        // 401 z api/v1/* — Bearer token odrzucony, to realny koniec sesji.
        // Cookies zostają nietknięte; czyści je dopiero jawne wylogowanie w AuthRepository.
        sessionRepository.setToken(null)
        sessionRepository.setLoggedIn(false)
        return null
    }
}
