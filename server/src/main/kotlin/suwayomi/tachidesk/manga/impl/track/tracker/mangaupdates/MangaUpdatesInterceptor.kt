package suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates

import okhttp3.Interceptor
import okhttp3.Response
import suwayomi.tachidesk.server.generated.BuildConfig
import java.io.IOException

class MangaUpdatesInterceptor(
    userId: Int,
    mangaUpdates: MangaUpdates,
) : Interceptor {
    private var token: String? = mangaUpdates.restoreSession(userId)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = token ?: throw IOException("Not authenticated with MangaUpdates")

        // Add the authorization header to the original request.
        val authRequest =
            originalRequest
                .newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .header("User-Agent", "Suwayomi ${BuildConfig.VERSION} (${BuildConfig.REVISION})")
                .build()

        return chain.proceed(authRequest)
    }

    fun newAuth(token: String?) {
        this.token = token
    }
}
