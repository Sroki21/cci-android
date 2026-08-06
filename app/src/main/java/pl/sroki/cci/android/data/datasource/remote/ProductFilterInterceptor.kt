package pl.sroki.cci.android.data.datasource.remote

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Zawęża listy kapsli do kapsli piwnych, dokładając `productId=1` do zapytania.
 *
 * Serwis kataloguje też inne rodzaje kapsli, a aplikacja jest wyłącznie o piwnych — filtr jest
 * globalny, żeby nie powtarzać go w każdym repozytorium. Dotyczy każdej **listy** kapsli, więc
 * także `/api/v1/countries/{id}/caps` i `/api/v1/categories/caps`; szczegół pojedynczego kapsla
 * (`/api/v1/caps/{id}`) nie kończy się na `/caps` i filtru nie dostaje.
 *
 * Wyjątkiem są zapytania z `in_collection`: backend gubi przy nich część kapsli, gdy dołożyć
 * `productId`, więc te listy filtruje się po stronie klienta (patrz `PurchasedCapsLocalStore`).
 */
class ProductFilterInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(if (request.needsFilter()) request.withProductFilter() else request)
    }

    private fun Request.needsFilter(): Boolean =
        method == "GET" &&
            url.encodedPath.endsWith(CAPS_LIST_SUFFIX) &&
            url.queryParameter(COLLECTION_QUERY) == null

    private fun Request.withProductFilter(): Request =
        newBuilder()
            .url(url.newBuilder().addQueryParameter(PRODUCT_QUERY, BEER_CAPS).build())
            .build()

    private companion object {
        const val CAPS_LIST_SUFFIX = "/caps"
        const val COLLECTION_QUERY = "in_collection"
        const val PRODUCT_QUERY = "productId"
        const val BEER_CAPS = "1"
    }
}
