package suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates

import io.github.reactivecircus.cache4k.Cache
import suwayomi.tachidesk.manga.impl.track.tracker.DeletableTrackService
import suwayomi.tachidesk.manga.impl.track.tracker.Tracker
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.dto.ListItem
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.dto.Rating
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.dto.copyTo
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.dto.toTrackSearch
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import kotlin.time.Duration.Companion.hours

class MangaUpdates(
    id: Int,
) : Tracker(id, "MangaUpdates"),
    DeletableTrackService {
    companion object {
        const val READING_LIST = 0
        const val WISH_LIST = 1
        const val COMPLETE_LIST = 2
        const val UNFINISHED_LIST = 3
        const val ON_HOLD_LIST = 4

        private val SCORE_LIST =
            (0..10)
                .flatMap { decimal ->
                    when (decimal) {
                        0 -> listOf("-")
                        10 -> listOf("10.0")
                        else ->
                            (0..9).map { fraction ->
                                "$decimal.$fraction"
                            }
                    }
                }
    }

    override val supportsTrackDeletion: Boolean = true

    private val interceptors =
        Cache
            .Builder<Int, MangaUpdatesInterceptor>()
            .expireAfterAccess(1.hours)
            .build()
    private val apis =
        Cache
            .Builder<Int, MangaUpdatesApi>()
            .expireAfterAccess(1.hours)
            .build()

    suspend fun interceptor(userId: Int): MangaUpdatesInterceptor =
        interceptors.get(userId) {
            MangaUpdatesInterceptor(userId, this)
        }

    suspend fun api(userId: Int): MangaUpdatesApi =
        apis.get(userId) {
            MangaUpdatesApi(interceptor(userId), client)
        }

    override fun getLogo(): String = "/static/tracker/manga_updates.png"

    override fun getStatusList(): List<Int> = listOf(READING_LIST, COMPLETE_LIST, ON_HOLD_LIST, UNFINISHED_LIST, WISH_LIST)

    override fun getStatus(status: Int): String? =
        when (status) {
            READING_LIST -> "Reading List"
            WISH_LIST -> "Wish List"
            COMPLETE_LIST -> "Complete List"
            ON_HOLD_LIST -> "On Hold List"
            UNFINISHED_LIST -> "Unfinished List"
            else -> null
        }

    override fun getReadingStatus(): Int = READING_LIST

    override fun getRereadingStatus(): Int = -1

    override fun getCompletionStatus(): Int = COMPLETE_LIST

    override fun getScoreList(userId: Int): List<String> = SCORE_LIST

    override fun indexToScore(
        userId: Int,
        index: Int,
    ): Float = if (index == 0) 0f else SCORE_LIST[index].toFloat()

    override fun displayScore(
        userId: Int,
        track: Track,
    ): String = track.score.toString()

    override suspend fun update(
        userId: Int,
        track: Track,
        didReadChapter: Boolean,
    ): Track {
        if (track.status != COMPLETE_LIST && didReadChapter) {
            track.status = READING_LIST
        }
        api(userId).updateSeriesListItem(track)
        return track
    }

    override suspend fun delete(
        userId: Int,
        track: Track,
    ) {
        api(userId).deleteSeriesFromList(track)
    }

    override suspend fun bind(
        userId: Int,
        track: Track,
        hasReadChapters: Boolean,
    ): Track =
        try {
            val (series, rating) = api(userId).getSeriesListItem(track)
            track.copyFrom(series, rating)
        } catch (e: Exception) {
            track.score = 0f
            api(userId).addSeriesToList(track, hasReadChapters)
            track
        }

    override suspend fun search(
        userId: Int,
        query: String,
    ): List<TrackSearch> =
        api(userId)
            .search(query)
            .map {
                it.toTrackSearch(id)
            }

    override suspend fun refresh(
        userId: Int,
        track: Track,
    ): Track {
        val (series, rating) = api(userId).getSeriesListItem(track)
        return track.copyFrom(series, rating)
    }

    private fun Track.copyFrom(
        item: ListItem,
        rating: Rating?,
    ): Track =
        apply {
            item.copyTo(this)
            score = rating?.rating ?: 0f
        }

    override suspend fun login(
        userId: Int,
        username: String,
        password: String,
    ) {
        val authenticated = api(userId).authenticate(username, password) ?: throw Throwable("Unable to login")
        saveCredentials(userId, authenticated.uid.toString(), authenticated.sessionToken)
        interceptor(userId).newAuth(authenticated.sessionToken)
    }

    fun restoreSession(userId: Int): String? = trackPreferences.getTrackPassword(userId, this)
}
