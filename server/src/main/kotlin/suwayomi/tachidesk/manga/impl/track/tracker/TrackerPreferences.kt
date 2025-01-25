package suwayomi.tachidesk.manga.impl.track.tracker

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.manga.impl.track.tracker.anilist.Anilist
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TrackerPreferences {
    private val preferenceStore =
        Injekt.get<Application>().getSharedPreferences("tracker", Context.MODE_PRIVATE)
    private val logger = KotlinLogging.logger {}

    fun getTrackUsername(
        userId: Int,
        sync: Tracker,
    ) = preferenceStore.getString(trackUsername(userId, sync.id), "")

    fun getTrackPassword(
        userId: Int,
        sync: Tracker,
    ) = preferenceStore.getString(trackPassword(userId, sync.id), "")

    fun trackAuthExpired(
        userId: Int,
        tracker: Tracker,
    ) = preferenceStore.getBoolean(
        trackTokenExpired(userId, tracker.id),
        false,
    )

    fun setTrackCredentials(
        userId: Int,
        sync: Tracker,
        username: String,
        password: String,
    ) {
        logger.debug { "setTrackCredentials: id=${sync.id} username=$username" }
        preferenceStore
            .edit()
            .putString(trackUsername(userId, sync.id), username)
            .putString(trackPassword(userId, sync.id), password)
            .putBoolean(trackTokenExpired(userId, sync.id), false)
            .apply()
    }

    fun getTrackToken(
        userId: Int,
        sync: Tracker,
    ) = preferenceStore.getString(trackToken(userId, sync.id), "")

    fun setTrackToken(
        userId: Int,
        sync: Tracker,
        token: String?,
    ) {
        logger.debug { "setTrackToken: id=${sync.id} token=$token" }
        if (token == null) {
            preferenceStore
                .edit()
                .remove(trackToken(userId, sync.id))
                .putBoolean(trackTokenExpired(userId, sync.id), false)
                .apply()
        } else {
            preferenceStore
                .edit()
                .putString(trackToken(userId, sync.id), token)
                .putBoolean(trackTokenExpired(userId, sync.id), false)
                .apply()
        }
    }

    fun setTrackTokenExpired(
        userId: Int,
        sync: Tracker,
    ) {
        preferenceStore
            .edit()
            .putBoolean(trackTokenExpired(userId, sync.id), true)
            .apply()
    }

    fun getScoreType(
        userId: Int,
        sync: Tracker,
    ) = preferenceStore.getString(scoreType(userId, sync.id), Anilist.POINT_10)

    fun setScoreType(
        userId: Int,
        sync: Tracker,
        scoreType: String,
    ) = preferenceStore
        .edit()
        .putString(scoreType(userId, sync.id), scoreType)
        .apply()

    fun autoUpdateTrack(userId: Int) = preferenceStore.getBoolean("pref_auto_update_manga_sync_key", true)

    fun trackUsername(
        userId: Int,
        trackerId: Int,
    ) = "pref_mangasync_username_${userId}_$trackerId"

    private fun trackPassword(
        userId: Int,
        trackerId: Int,
    ) = "pref_mangasync_password_${userId}_$trackerId"

    private fun trackToken(
        userId: Int,
        trackerId: Int,
    ) = "track_token_${userId}_$trackerId"

    private fun trackTokenExpired(
        userId: Int,
        trackerId: Int,
    ) = "track_token_expired_${userId}_$trackerId"

    private fun scoreType(
        userId: Int,
        trackerId: Int,
    ) = "score_type_${userId}_$trackerId"
}
