package suwayomi.tachidesk.manga.impl.track.tracker

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import uy.kohesive.injekt.injectLazy
import java.io.IOException

abstract class Tracker(
    val id: Int,
    val name: String,
) {
    val trackPreferences = TrackerPreferences
    private val networkService: NetworkHelper by injectLazy()

    open val client: OkHttpClient
        get() = networkService.client

    // Application and remote support for reading dates
    open val supportsReadingDates: Boolean = false

    abstract val supportsTrackDeletion: Boolean

    override fun toString() = "$name ($id)"

    abstract fun getLogo(): String

    abstract fun getStatusList(): List<Int>

    abstract fun getStatus(status: Int): String?

    abstract fun getReadingStatus(): Int

    abstract fun getRereadingStatus(): Int

    abstract fun getCompletionStatus(): Int

    abstract fun getScoreList(userId: Int): List<String>

    open fun indexToScore(
        userId: Int,
        index: Int,
    ): Float = index.toFloat()

    abstract fun displayScore(
        userId: Int,
        track: Track,
    ): String

    abstract suspend fun update(
        userId: Int,
        track: Track,
        didReadChapter: Boolean = false,
    ): Track

    abstract suspend fun bind(
        userId: Int,
        track: Track,
        hasReadChapters: Boolean = false,
    ): Track

    abstract suspend fun search(
        userId: Int,
        query: String,
    ): List<TrackSearch>

    abstract suspend fun refresh(
        userId: Int,
        track: Track,
    ): Track

    open fun authUrl(): String? = null

    open suspend fun authCallback(
        userId: Int,
        url: String,
    ) {}

    abstract suspend fun login(
        userId: Int,
        username: String,
        password: String,
    )

    open suspend fun logout(userId: Int) {
        trackPreferences.setTrackCredentials(userId, this, "", "")
    }

    open fun isLoggedIn(userId: Int): Boolean =
        getUsername(userId).isNotEmpty() &&
            getPassword(userId).isNotEmpty()

    fun getUsername(userId: Int) = trackPreferences.getTrackUsername(userId, this) ?: ""

    fun getPassword(userId: Int) = trackPreferences.getTrackPassword(userId, this) ?: ""

    fun saveCredentials(
        userId: Int,
        username: String,
        password: String,
    ) {
        trackPreferences.setTrackCredentials(userId, this, username, password)
    }

    fun getIfAuthExpired(userId: Int): Boolean = trackPreferences.trackAuthExpired(userId, this)

    fun setAuthExpired(userId: Int) {
        trackPreferences.setTrackTokenExpired(userId, this)
    }
}

fun String.extractToken(key: String): String? {
    val regex = "$key=(.*?)$".toRegex()
    for (s in this.split("&")) {
        val matchResult = regex.find(s)
        if (matchResult?.groups?.get(1) != null) {
            return matchResult.groups[1]!!.value
        }
    }
    return null
}

class TokenExpired : IOException("Token is expired, re-logging required")

class TokenRefreshFailed : IOException("Token refresh failed")
