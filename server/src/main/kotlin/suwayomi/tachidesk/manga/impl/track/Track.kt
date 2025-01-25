package suwayomi.tachidesk.manga.impl.track

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.track.tracker.DeletableTrackService
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.toTrack
import suwayomi.tachidesk.manga.impl.track.tracker.model.toTrackRecordDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaTrackerDataClass
import suwayomi.tachidesk.manga.model.dataclass.TrackSearchDataClass
import suwayomi.tachidesk.manga.model.dataclass.TrackerDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.TrackSearchTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.insertAll
import suwayomi.tachidesk.server.generated.BuildConfig
import java.io.InputStream

object Track {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = KotlinLogging.logger {}

    fun getTrackerList(userId: Int): List<TrackerDataClass> {
        val trackers = TrackerManager.services
        return trackers.map {
            val isLogin = it.isLoggedIn(userId)
            val authUrl = if (isLogin) null else it.authUrl()
            TrackerDataClass(
                id = it.id,
                name = it.name,
                icon = proxyThumbnailUrl(it.id),
                isLogin = isLogin,
                authUrl = authUrl,
            )
        }
    }

    suspend fun login(
        userId: Int,
        input: LoginInput,
    ) {
        val tracker = TrackerManager.getTracker(input.trackerId)!!
        if (input.callbackUrl != null) {
            tracker.authCallback(userId, input.callbackUrl)
        } else {
            tracker.login(userId, input.username ?: "", input.password ?: "")
        }
    }

    suspend fun logout(
        userId: Int,
        input: LogoutInput,
    ) {
        val tracker = TrackerManager.getTracker(input.trackerId)!!
        tracker.logout(userId)
    }

    fun proxyThumbnailUrl(trackerId: Int): String = "/api/v1/track/$trackerId/thumbnail"

    fun getTrackerThumbnail(trackerId: Int): Pair<InputStream, String> {
        val tracker = TrackerManager.getTracker(trackerId)!!
        val logo = BuildConfig::class.java.getResourceAsStream(tracker.getLogo())!!
        return logo to "image/png"
    }

    fun getTrackRecordsByMangaId(
        userId: Int,
        mangaId: Int,
    ): List<MangaTrackerDataClass> {
        val recordMap =
            transaction {
                TrackRecordTable
                    .selectAll()
                    .where { TrackRecordTable.mangaId eq mangaId and (TrackRecordTable.user eq userId) }
                    .map { it.toTrackRecordDataClass() }
            }.associateBy { it.trackerId }

        val trackers = TrackerManager.services
        return trackers.map {
            val record = recordMap[it.id]
            if (record != null) {
                val track =
                    Track.create(it.id).also { t ->
                        t.score = record.score.toFloat()
                    }
                record.scoreString = it.displayScore(userId, track)
            }
            MangaTrackerDataClass(
                id = it.id,
                name = it.name,
                icon = proxyThumbnailUrl(it.id),
                statusList = it.getStatusList(),
                statusTextMap = it.getStatusList().associateWith { k -> it.getStatus(k).orEmpty() },
                scoreList = it.getScoreList(userId),
                record = record,
            )
        }
    }

    suspend fun search(
        userId: Int,
        input: SearchInput,
    ): List<TrackSearchDataClass> {
        val tracker = TrackerManager.getTracker(input.trackerId)!!
        val list = tracker.search(userId, input.title)
        return list.insertAll().map {
            TrackSearchDataClass(
                id = it[TrackSearchTable.id].value,
                trackerId = it[TrackSearchTable.trackerId],
                remoteId = it[TrackSearchTable.remoteId],
                title = it[TrackSearchTable.title],
                totalChapters = it[TrackSearchTable.totalChapters],
                trackingUrl = it[TrackSearchTable.trackingUrl],
                coverUrl = it[TrackSearchTable.coverUrl],
                summary = it[TrackSearchTable.summary],
                publishingStatus = it[TrackSearchTable.publishingStatus],
                publishingType = it[TrackSearchTable.publishingType],
                startDate = it[TrackSearchTable.startDate],
            )
        }
    }

    private fun ResultRow.toTrackFromSearch(mangaId: Int): Track =
        Track.create(this[TrackSearchTable.trackerId]).also {
            it.manga_id = mangaId
            it.media_id = this[TrackSearchTable.remoteId]
            it.title = this[TrackSearchTable.title]
            it.total_chapters = this[TrackSearchTable.totalChapters]
            it.tracking_url = this[TrackSearchTable.trackingUrl]
        }

    suspend fun bind(
        userId: Int,
        mangaId: Int,
        trackerId: Int,
        remoteId: Long,
    ) {
        val track =
            transaction {
                TrackSearchTable
                    .selectAll()
                    .where {
                        TrackSearchTable.trackerId eq trackerId and
                            (TrackSearchTable.remoteId eq remoteId)
                    }.firstOrNull()
                    ?.toTrackFromSearch(mangaId)
                    ?: TrackRecordTable
                        .selectAll()
                        .where {
                            (TrackRecordTable.trackerId eq trackerId) and
                                (TrackRecordTable.remoteId eq remoteId) and
                                (TrackRecordTable.user eq userId)
                        }.first()
                        .toTrack()
                        .apply {
                            manga_id = mangaId
                        }
            }
        val tracker = TrackerManager.getTracker(trackerId)!!

        val chapter = queryMaxReadChapter(userId, mangaId)
        val hasReadChapters = chapter != null
        val chapterNumber = chapter?.get(ChapterTable.chapter_number)

        tracker.bind(userId, track, hasReadChapters)
        val recordId = upsertTrackRecord(userId, track)

        var lastChapterRead: Double? = null
        var startDate: Long? = null
        if (chapterNumber != null && chapterNumber > 0 && chapterNumber > track.last_chapter_read) {
            lastChapterRead = chapterNumber.toDouble()
        }
        if (track.started_reading_date <= 0) {
            val oldestChapter =
                transaction {
                    ChapterTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where {
                            (ChapterTable.manga eq mangaId) and (ChapterUserTable.isRead eq true)
                        }.orderBy(ChapterUserTable.lastReadAt to SortOrder.ASC)
                        .limit(1)
                        .firstOrNull()
                }
            if (oldestChapter != null) {
                startDate = oldestChapter[ChapterUserTable.lastReadAt] * 1000
            }
        }
        if (lastChapterRead != null || startDate != null) {
            val trackUpdate =
                UpdateInput(
                    recordId = recordId,
                    lastChapterRead = lastChapterRead,
                    startDate = startDate,
                )
            update(userId, trackUpdate)
        }
    }

    suspend fun refresh(
        userId: Int,
        recordId: Int,
    ) {
        val recordDb =
            transaction {
                TrackRecordTable.selectAll().where { TrackRecordTable.id eq recordId and (TrackRecordTable.user eq userId) }.first()
            }

        val tracker = TrackerManager.getTracker(recordDb[TrackRecordTable.trackerId])!!

        val track = recordDb.toTrack()
        tracker.refresh(userId, track)
        upsertTrackRecord(userId, track)
    }

    suspend fun unbind(
        userId: Int,
        recordId: Int,
        deleteRemoteTrack: Boolean? = false,
    ) {
        val recordDb =
            transaction {
                TrackRecordTable.selectAll().where { TrackRecordTable.id eq recordId and (TrackRecordTable.user eq userId) }.first()
            }

        val tracker = TrackerManager.getTracker(recordDb[TrackRecordTable.trackerId])

        if (deleteRemoteTrack == true && tracker is DeletableTrackService) {
            tracker.delete(userId, recordDb.toTrack())
        }

        transaction {
            TrackRecordTable.deleteWhere { TrackRecordTable.id eq recordId and (TrackRecordTable.user eq userId) }
        }
    }

    suspend fun update(
        userId: Int,
        input: UpdateInput,
    ) {
        if (input.unbind == true) {
            unbind(userId, input.recordId)
            return
        }
        val recordDb =
            transaction {
                TrackRecordTable.selectAll().where { TrackRecordTable.id eq input.recordId and (TrackRecordTable.user eq userId) }.first()
            }

        val tracker = TrackerManager.getTracker(recordDb[TrackRecordTable.trackerId])!!

        if (input.status != null) {
            recordDb[TrackRecordTable.status] = input.status
            if (input.status == tracker.getCompletionStatus() && recordDb[TrackRecordTable.totalChapters] != 0) {
                recordDb[TrackRecordTable.lastChapterRead] = recordDb[TrackRecordTable.totalChapters]
            }
        }
        if (input.lastChapterRead != null) {
            if (recordDb[TrackRecordTable.lastChapterRead] == 0.0 &&
                recordDb[TrackRecordTable.lastChapterRead] < input.lastChapterRead &&
                recordDb[TrackRecordTable.status] != tracker.getRereadingStatus()
            ) {
                recordDb[TrackRecordTable.status] = tracker.getReadingStatus()
            }
            recordDb[TrackRecordTable.lastChapterRead] = input.lastChapterRead
            if (recordDb[TrackRecordTable.totalChapters] != 0 &&
                input.lastChapterRead.toInt() == recordDb[TrackRecordTable.totalChapters]
            ) {
                recordDb[TrackRecordTable.status] = tracker.getCompletionStatus()
                recordDb[TrackRecordTable.finishDate] = System.currentTimeMillis()
            }
        }
        if (input.scoreString != null) {
            val score = tracker.indexToScore(userId, tracker.getScoreList(userId).indexOf(input.scoreString))
            // conversion issues between Float <-> Double so convert to string before double
            recordDb[TrackRecordTable.score] = score.toString().toDouble()
        }
        if (input.startDate != null) {
            recordDb[TrackRecordTable.startDate] = input.startDate
        }
        if (input.finishDate != null) {
            recordDb[TrackRecordTable.finishDate] = input.finishDate
        }

        val track = recordDb.toTrack()
        tracker.update(userId, track)

        upsertTrackRecord(userId, track)
    }

    fun asyncTrackChapter(
        userId: Int,
        mangaIds: Set<Int>,
    ) {
        if (!TrackerManager.hasLoggedTracker(userId)) {
            return
        }
        scope.launch {
            mangaIds.forEach {
                trackChapter(userId, it)
            }
        }
    }

    suspend fun trackChapter(
        userId: Int,
        mangaId: Int,
    ) {
        val chapter = queryMaxReadChapter(userId, mangaId)
        val chapterNumber = chapter?.get(ChapterTable.chapter_number)

        logger.info {
            "trackChapter(mangaId= $mangaId): maxReadChapter= #$chapterNumber ${chapter?.get(ChapterTable.name)}"
        }

        if (chapterNumber != null && chapterNumber > 0) {
            trackChapter(userId, mangaId, chapterNumber.toDouble())
        }
    }

    private fun queryMaxReadChapter(
        userId: Int,
        mangaId: Int,
    ): ResultRow? =
        transaction {
            ChapterTable
                .getWithUserData(userId)
                .selectAll()
                .where { (ChapterTable.manga eq mangaId) and (ChapterUserTable.isRead eq true) }
                .orderBy(ChapterTable.chapter_number to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
        }

    private suspend fun trackChapter(
        userId: Int,
        mangaId: Int,
        chapterNumber: Double,
    ) {
        val records =
            transaction {
                TrackRecordTable
                    .selectAll()
                    .where { TrackRecordTable.mangaId eq mangaId and (TrackRecordTable.user eq userId) }
                    .toList()
            }

        records.forEach {
            try {
                trackChapterForTracker(userId, it, chapterNumber)
            } catch (e: Exception) {
                KotlinLogging
                    .logger("${logger.name}::trackChapter(mangaId= $mangaId, chapterNumber= $chapterNumber)")
                    .error(e) { "failed due to" }
            }
        }
    }

    private suspend fun trackChapterForTracker(
        userId: Int,
        it: ResultRow,
        chapterNumber: Double,
    ) {
        val tracker = TrackerManager.getTracker(it[TrackRecordTable.trackerId]) ?: return
        val track = it.toTrack()

        val log =
            KotlinLogging.logger {
                "${logger.name}::trackChapterForTracker(chapterNumber= $chapterNumber, tracker= ${tracker.id}, recordId= ${track.id})"
            }
        log.debug { "called for $tracker, ${track.title} (recordId= ${track.id}, mangaId= ${track.manga_id})" }

        val localLastReadChapter = it[TrackRecordTable.lastChapterRead]

        if (localLastReadChapter == chapterNumber) {
            log.debug { "new chapter is the same as the local last read chapter" }
            return
        }

        if (!tracker.isLoggedIn(userId)) {
            upsertTrackRecord(userId, track)
            return
        }

        tracker.refresh(userId, track)
        upsertTrackRecord(userId, track)

        val lastChapterRead = track.last_chapter_read

        log.debug { "remoteLastReadChapter= $lastChapterRead" }

        if (chapterNumber > lastChapterRead) {
            track.last_chapter_read = chapterNumber.toFloat()
            tracker.update(userId, track, true)
            upsertTrackRecord(userId, track)
        }
    }

    fun upsertTrackRecord(
        userId: Int,
        track: Track,
    ): Int =
        transaction {
            val existingRecord =
                TrackRecordTable
                    .selectAll()
                    .where {
                        (TrackRecordTable.mangaId eq track.manga_id) and
                            (TrackRecordTable.trackerId eq track.sync_id) and
                            (TrackRecordTable.user eq userId)
                    }.singleOrNull()

            if (existingRecord != null) {
                updateTrackRecord(userId, track)
                existingRecord[TrackRecordTable.id].value
            } else {
                insertTrackRecord(userId, track)
            }
        }

    fun updateTrackRecord(
        userId: Int,
        track: Track,
    ): Int =
        transaction {
            TrackRecordTable.update(
                {
                    (TrackRecordTable.user eq userId) and
                        (TrackRecordTable.mangaId eq track.manga_id) and
                        (TrackRecordTable.trackerId eq track.sync_id)
                },
            ) {
                it[remoteId] = track.media_id
                it[libraryId] = track.library_id
                it[title] = track.title
                it[lastChapterRead] = track.last_chapter_read.toDouble()
                it[totalChapters] = track.total_chapters
                it[status] = track.status
                it[score] = track.score.toDouble()
                it[remoteUrl] = track.tracking_url
                it[startDate] = track.started_reading_date
                it[finishDate] = track.finished_reading_date
            }
        }

    fun insertTrackRecord(
        userId: Int,
        track: Track,
    ): Int =
        transaction {
            TrackRecordTable
                .insertAndGetId {
                    it[mangaId] = track.manga_id
                    it[trackerId] = track.sync_id
                    it[remoteId] = track.media_id
                    it[libraryId] = track.library_id
                    it[title] = track.title
                    it[lastChapterRead] = track.last_chapter_read.toDouble()
                    it[totalChapters] = track.total_chapters
                    it[status] = track.status
                    it[score] = track.score.toDouble()
                    it[remoteUrl] = track.tracking_url
                    it[startDate] = track.started_reading_date
                    it[finishDate] = track.finished_reading_date
                    it[user] = userId
                }.value
        }

    @Serializable
    data class LoginInput(
        val trackerId: Int,
        val callbackUrl: String? = null,
        val username: String? = null,
        val password: String? = null,
    )

    @Serializable
    data class LogoutInput(
        val trackerId: Int,
    )

    @Serializable
    data class SearchInput(
        val trackerId: Int,
        val title: String,
    )

    @Serializable
    data class UpdateInput(
        val recordId: Int,
        val status: Int? = null,
        val lastChapterRead: Double? = null,
        val scoreString: String? = null,
        val startDate: Long? = null,
        val finishDate: Long? = null,
        val unbind: Boolean? = null,
    )
}
