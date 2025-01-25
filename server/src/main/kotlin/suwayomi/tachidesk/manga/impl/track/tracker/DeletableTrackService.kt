package suwayomi.tachidesk.manga.impl.track.tracker

import suwayomi.tachidesk.manga.impl.track.tracker.model.Track

/**
 * For track services api that support deleting a manga entry for a user's list
 */
interface DeletableTrackService {
    suspend fun delete(
        userId: Int,
        track: Track,
    )
}
