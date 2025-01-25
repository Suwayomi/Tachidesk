package suwayomi.tachidesk.manga.impl.track.tracker.anilist

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

class Anilist(
    id: Int,
) : Tracker(id, "AniList"),
    DeletableTrackService {
    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val ON_HOLD = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 5
        const val REREADING = 6

        const val POINT_100 = "POINT_100"
        const val POINT_10 = "POINT_10"
        const val POINT_10_DECIMAL = "POINT_10_DECIMAL"
        const val POINT_5 = "POINT_5"
        const val POINT_3 = "POINT_3"
    }

    override val supportsTrackDeletion: Boolean = true

    private val json: Json by injectLazy()

    private val interceptors =
        Cache
            .Builder<Int, AnilistInterceptor>()
            .expireAfterAccess(1.hours)
            .build()
    private val apis =
        Cache
            .Builder<Int, AnilistApi>()
            .expireAfterAccess(1.hours)
            .build()

    suspend fun interceptor(userId: Int): AnilistInterceptor =
        interceptors.get(userId) {
            AnilistInterceptor(userId, this)
        }

    suspend fun api(userId: Int): AnilistApi =
        apis.get(userId) {
            AnilistApi(client, interceptor(userId))
        }

    override val supportsReadingDates: Boolean = true

    private val logger = KotlinLogging.logger {}

    override fun getLogo(): String = "/static/tracker/anilist.png"

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

    override fun getScoreList(userId: Int): List<String> =
        when (trackPreferences.getScoreType(userId, this)) {
            // 10 point
            POINT_10 -> IntRange(0, 10).map(Int::toString)
            // 100 point
            POINT_100 -> IntRange(0, 100).map(Int::toString)
            // 5 stars
            POINT_5 -> IntRange(0, 5).map { "$it ★" }
            // Smiley
            POINT_3 -> listOf("-", "😦", "😐", "😊")
            // 10 point decimal
            POINT_10_DECIMAL -> IntRange(0, 100).map { (it / 10f).toString() }
            else -> throw Exception("Unknown score type")
        }

    override fun indexToScore(
        userId: Int,
        index: Int,
    ): Float =
        when (trackPreferences.getScoreType(userId, this)) {
            // 10 point
            POINT_10 -> index * 10f
            // 100 point
            POINT_100 -> index.toFloat()
            // 5 stars
            POINT_5 ->
                when (index) {
                    0 -> 0f
                    else -> index * 20f - 10f
                }
            // Smiley
            POINT_3 ->
                when (index) {
                    0 -> 0f
                    else -> index * 25f + 10f
                }
            // 10 point decimal
            POINT_10_DECIMAL -> index.toFloat()
            else -> throw Exception("Unknown score type")
        }

    override fun displayScore(
        userId: Int,
        track: Track,
    ): String {
        val score = track.score
        return when (val type = trackPreferences.getScoreType(userId, this)) {
            POINT_5 ->
                when (score) {
                    0f -> "0 ★"
                    else -> "${((score + 10) / 20).toInt()} ★"
                }
            POINT_3 ->
                when {
                    score == 0f -> "0"
                    score <= 35 -> "😦"
                    score <= 60 -> "😐"
                    else -> "😊"
                }
            else -> track.toAnilistScore(type)
        }
    }

    private suspend fun add(
        userId: Int,
        track: Track,
    ): Track = api(userId).addLibManga(track)

    override suspend fun update(
        userId: Int,
        track: Track,
        didReadChapter: Boolean,
    ): Track {
        // If user was using API v1 fetch library_id
        if (track.library_id == null || track.library_id!! == 0L) {
            val libManga =
                api(userId).findLibManga(track, getUsername(userId).toInt())
                    ?: throw Exception("$track not found on user library")
            track.library_id = libManga.library_id
        }

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

        return api(userId).updateLibManga(track)
    }

    override suspend fun delete(
        userId: Int,
        track: Track,
    ) {
        if (track.library_id == null || track.library_id!! == 0L) {
            val libManga = api(userId).findLibManga(track, getUsername(userId).toInt()) ?: return
            track.library_id = libManga.library_id
        }

        api(userId).deleteLibManga(track)
    }

    override suspend fun bind(
        userId: Int,
        track: Track,
        hasReadChapters: Boolean,
    ): Track {
        val remoteTrack = api(userId).findLibManga(track, getUsername(userId).toInt())
        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack)
            track.library_id = remoteTrack.library_id

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
    ): List<TrackSearch> = api(userId).search(query)

    override suspend fun refresh(
        userId: Int,
        track: Track,
    ): Track {
        val remoteTrack = api(userId).getLibManga(track, getUsername(userId).toInt())
        track.copyPersonalFrom(remoteTrack)
        track.title = remoteTrack.title
        track.total_chapters = remoteTrack.total_chapters
        return track
    }

    override fun authUrl(): String = AnilistApi.authUrl().toString()

    override suspend fun authCallback(
        userId: Int,
        url: String,
    ) {
        val token = url.extractToken("access_token") ?: throw IOException("cannot find token")
        login(userId, token)
    }

    override suspend fun login(
        userId: Int,
        username: String,
        password: String,
    ) = login(userId, password)

    private suspend fun login(
        userId: Int,
        token: String,
    ) {
        try {
            logger.debug { "login $token" }
            val oauth = api(userId).createOAuth(token)
            interceptor(userId).setAuth(oauth)
            val (username, scoreType) = api(userId).getCurrentUser()
            trackPreferences.setScoreType(userId, this, scoreType)
            saveCredentials(userId, username.toString(), oauth.access_token)
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
