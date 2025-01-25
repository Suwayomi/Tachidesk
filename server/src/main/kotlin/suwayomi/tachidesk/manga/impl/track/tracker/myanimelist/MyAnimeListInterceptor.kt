package suwayomi.tachidesk.manga.impl.track.tracker.myanimelist

import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import suwayomi.tachidesk.manga.impl.track.tracker.TokenExpired
import suwayomi.tachidesk.manga.impl.track.tracker.TokenRefreshFailed
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class MyAnimeListInterceptor(
    private val userId: Int,
    private val myanimelist: MyAnimeList,
) : Interceptor {
    private val json: Json by injectLazy()

    private var oauth: OAuth? = myanimelist.loadOAuth(userId)

    override fun intercept(chain: Interceptor.Chain): Response {
        if (myanimelist.getIfAuthExpired(userId)) {
            throw TokenExpired()
        }
        val originalRequest = chain.request()

        if (oauth?.isExpired() == true) {
            refreshToken(chain)
        }

        if (oauth == null) {
            throw IOException("MAL: User is not authenticated")
        }

        // Add the authorization header to the original request
        val authRequest =
            originalRequest
                .newBuilder()
                .addHeader("Authorization", "Bearer ${oauth!!.access_token}")
                .header("User-Agent", "Suwayomi v${AppInfo.getVersionName()}")
                .build()

        return chain.proceed(authRequest)
    }

    /**
     * Called when the user authenticates with MyAnimeList for the first time. Sets the refresh token
     * and the oauth object.
     */
    fun setAuth(oauth: OAuth?) {
        this.oauth = oauth
        myanimelist.saveOAuth(userId, oauth)
    }

    private fun refreshToken(chain: Interceptor.Chain): OAuth =
        synchronized(this) {
            if (myanimelist.getIfAuthExpired(userId)) throw TokenExpired()
            oauth?.takeUnless { it.isExpired() }?.let { return@synchronized it }

            val response =
                try {
                    chain.proceed(MyAnimeListApi.refreshTokenRequest(oauth!!))
                } catch (_: Throwable) {
                    throw TokenRefreshFailed()
                }

            if (response.code == 401) {
                myanimelist.setAuthExpired(userId)
                throw TokenExpired()
            }

            return runCatching {
                if (response.isSuccessful) {
                    with(json) { response.parseAs<OAuth>() }
                } else {
                    response.close()
                    null
                }
            }.getOrNull()
                ?.also(::setAuth)
                ?: throw TokenRefreshFailed()
        }
}
