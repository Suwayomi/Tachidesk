package suwayomi.tachidesk.manga.impl.track.tracker.myanimelist

import android.annotation.StringRes
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.reactivecircus.cache4k.Cache
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.track.tracker.DeletableTrackService
import suwayomi.tachidesk.manga.impl.track.tracker.Tracker
import suwayomi.tachidesk.manga.impl.track.tracker.extractToken
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import kotlin.time.Duration.Companion.hours

class MyAnimeList(
    id: Int,
) : Tracker(id, "MyAnimeList"),
    DeletableTrackService {
    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 6
        const val REREADING = 7

        private const val SEARCH_ID_PREFIX = "id:"
        private const val SEARCH_LIST_PREFIX = "my:"
    }

    override val supportsTrackDeletion: Boolean = true

    private val json: Json by injectLazy()

    private val interceptors =
        Cache
            .Builder<Int, MyAnimeListInterceptor>()
            .expireAfterAccess(1.hours)
            .build()
    private val apis =
        Cache
            .Builder<Int, MyAnimeListApi>()
            .expireAfterAccess(1.hours)
            .build()

    suspend fun interceptor(userId: Int): MyAnimeListInterceptor =
        interceptors.get(userId) {
            MyAnimeListInterceptor(userId, this)
        }

    suspend fun api(userId: Int): MyAnimeListApi =
        apis.get(userId) {
            MyAnimeListApi(client, interceptor(userId))
        }

    override val supportsReadingDates: Boolean = true

    private val logger = KotlinLogging.logger {}

    override fun getLogo(): String = "/static/tracker/mal.png"

    override fun getStatusList(): List<Int> = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ, REREADING)

    @StringRes
    override fun getStatus(status: Int): String? =
        when (status) {
            READING -> "Reading"
            PLAN_TO_READ -> "Plan to read"
            COMPLETED -> "Completed"
            ON_HOLD -> "On hold"
            DROPPED -> "Dropped"
            REREADING -> "Rereading"
            else -> null
        }

    override fun getReadingStatus(): Int = READING

    override fun getRereadingStatus(): Int = REREADING

    override fun getCompletionStatus(): Int = COMPLETED

    override fun getScoreList(userId: Int): List<String> = IntRange(0, 10).map(Int::toString)

    override fun displayScore(
        userId: Int,
        track: Track,
    ): String = track.score.toInt().toString()

    private suspend fun add(
        userId: Int,
        track: Track,
    ): Track = api(userId).updateItem(track)

    override suspend fun update(
        userId: Int,
        track: Track,
        didReadChapter: Boolean,
    ): Track {
        if (track.status != COMPLETED) {
            if (didReadChapter) {
                if (track.last_chapter_read.toInt() == track.total_chapters && track.total_chapters > 0) {
                    track.status = COMPLETED
                    track.finished_reading_date = System.currentTimeMillis()
                } else if (track.status != REREADING) {
                    track.status = READING
                    if (track.last_chapter_read == 1F) {
                        track.started_reading_date = System.currentTimeMillis()
                    }
                }
            }
        }

        return api(userId).updateItem(track)
    }

    override suspend fun delete(
        userId: Int,
        track: Track,
    ) {
        api(userId).deleteItem(track)
    }

    override suspend fun bind(
        userId: Int,
        track: Track,
        hasReadChapters: Boolean,
    ): Track {
        val remoteTrack = api(userId).findListItem(track)
        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack)
            track.media_id = remoteTrack.media_id

            if (track.status != COMPLETED) {
                val isRereading = track.status == REREADING
                track.status = if (isRereading.not() && hasReadChapters) READING else track.status
            }

            update(userId, track)
        } else {
            // Set default fields if it's not found in the list
            track.status = if (hasReadChapters) READING else PLAN_TO_READ
            track.score = 0F
            add(userId, track)
        }
    }

    override suspend fun search(
        userId: Int,
        query: String,
    ): List<TrackSearch> {
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).toIntOrNull()?.let { id ->
                return listOf(api(userId).getMangaDetails(id))
            }
        }

        if (query.startsWith(SEARCH_LIST_PREFIX)) {
            query.substringAfter(SEARCH_LIST_PREFIX).let { title ->
                return api(userId).findListItems(title)
            }
        }

        return api(userId).search(query)
    }

    override suspend fun refresh(
        userId: Int,
        track: Track,
    ): Track =
        api(userId).findListItem(track)
            ?: add(userId, track)

    override fun authUrl(): String = MyAnimeListApi.authUrl().toString()

    override suspend fun authCallback(
        userId: Int,
        url: String,
    ) {
        val code = url.extractToken("code") ?: throw IOException("cannot find token")
        login(userId, code)
    }

    override suspend fun login(
        userId: Int,
        username: String,
        password: String,
    ) = login(userId, password)

    suspend fun login(
        userId: Int,
        authCode: String,
    ) {
        try {
            logger.debug { "login $authCode" }
            val oauth = api(userId).getAccessToken(authCode)
            interceptor(userId).setAuth(oauth)
            val username = api(userId).getCurrentUser()
            saveCredentials(userId, username, oauth.access_token)
        } catch (e: Throwable) {
            logger.error(e) { "oauth err" }
            logout(userId)
            throw e
        }
    }

    override suspend fun logout(userId: Int) {
        super.logout(userId)
        trackPreferences.setTrackToken(userId, this, null)
        interceptor(userId).setAuth(null)
    }

    fun saveOAuth(
        userId: Int,
        oAuth: OAuth?,
    ) {
        trackPreferences.setTrackToken(userId, this, json.encodeToString(oAuth))
    }

    fun loadOAuth(userId: Int): OAuth? =
        try {
            json.decodeFromString<OAuth>(trackPreferences.getTrackToken(userId, this)!!)
        } catch (e: Exception) {
            logger.error(e) { "loadOAuth err" }
            null
        }
}
