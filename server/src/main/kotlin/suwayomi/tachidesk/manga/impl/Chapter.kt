package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.ChapterRecognition
import eu.kanade.tachiyomi.util.chapter.ChapterSanitizer.sanitize
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.reactivecircus.cache4k.Cache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.Manga.getManga
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.download.DownloadManager.EnqueueInput
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.PaginatedList
import suwayomi.tachidesk.manga.model.dataclass.paginatedFrom
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.time.Instant
import java.util.TreeSet
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes

private fun List<ChapterDataClass>.removeDuplicates(currentChapter: ChapterDataClass): List<ChapterDataClass> =
    groupBy { it.chapterNumber }
        .map { (_, chapters) ->
            chapters.find { it.id == currentChapter.id }
                ?: chapters.find { it.scanlator == currentChapter.scanlator }
                ?: chapters.first()
        }

object Chapter {
    private val logger = KotlinLogging.logger { }

    /** get chapter list when showing a manga */
    suspend fun getChapterList(
        mangaId: Int,
        onlineFetch: Boolean = false,
    ): List<ChapterDataClass> =
        if (onlineFetch) {
            getSourceChapters(mangaId)
        } else {
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                    .map {
                        ChapterTable.toDataClass(it)
                    }
            }.ifEmpty {
                getSourceChapters(mangaId)
            }
        }

    fun getCountOfMangaChapters(mangaId: Int): Int =
        transaction {
            ChapterTable
                .selectAll()
                .where { ChapterTable.manga eq mangaId }
                .count()
                .toInt()
        }

    private suspend fun getSourceChapters(mangaId: Int): List<ChapterDataClass> {
        val chapterList = fetchChapterList(mangaId)

        val dbChapterMap =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { ChapterTable.manga eq mangaId }
                    .associateBy({ it[ChapterTable.url] }, { it })
            }

        val chapterIds = chapterList.map { dbChapterMap.getValue(it.url)[ChapterTable.id] }
        val chapterMetas = getChaptersMetaMaps(chapterIds)

        return chapterList.mapIndexed { index, it ->

            val dbChapter = dbChapterMap.getValue(it.url)

            ChapterDataClass(
                id = dbChapter[ChapterTable.id].value,
                url = it.url,
                name = it.name,
                uploadDate = it.date_upload,
                chapterNumber = it.chapter_number,
                scanlator = it.scanlator,
                mangaId = mangaId,
                read = dbChapter[ChapterTable.isRead],
                bookmarked = dbChapter[ChapterTable.isBookmarked],
                lastPageRead = dbChapter[ChapterTable.lastPageRead],
                lastReadAt = dbChapter[ChapterTable.lastReadAt],
                index = chapterList.size - index,
                fetchedAt = dbChapter[ChapterTable.fetchedAt],
                realUrl = dbChapter[ChapterTable.realUrl],
                downloaded = dbChapter[ChapterTable.isDownloaded],
                pageCount = dbChapter[ChapterTable.pageCount],
                chapterCount = chapterList.size,
                meta = chapterMetas.getValue(dbChapter[ChapterTable.id]),
            )
        }
    }

    val map: Cache<Int, Mutex> =
        Cache
            .Builder<Int, Mutex>()
            .expireAfterAccess(10.minutes)
            .build()

    suspend fun fetchChapterList(mangaId: Int): List<SChapter> {
        val mutex = map.get(mangaId) { Mutex() }
        val chapterList =
            mutex.withLock {
                val manga = getManga(mangaId)
                val source = getCatalogueSourceOrStub(manga.sourceId.toLong())

                val sManga =
                    SManga.create().apply {
                        title = manga.title
                        url = manga.url
                        description = manga.description
                    }

                val currentLatestChapterNumber = Manga.getLatestChapter(mangaId)?.chapterNumber ?: 0f
                val numberOfCurrentChapters = getCountOfMangaChapters(mangaId)

                val chapters = source.getChapterList(sManga)
                // it's possible that the source returns a list containing chapters with the same url
                // once such duplicated chapters have been added, they aren't being removed anymore as long as there is
                // a chapter with the same url in the fetched chapter list, even if the duplicated chapter itself
                // does not exist anymore on the source
                val uniqueChapters = chapters.distinctBy { it.url }

                if (uniqueChapters.isEmpty()) {
                    throw Exception("No chapters found")
                }

                // Recognize number for new chapters.
                uniqueChapters.forEach { chapter ->
                    (source as? HttpSource)?.prepareNewChapter(chapter, sManga)
                    val chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, chapter.name, chapter.chapter_number.toDouble())
                    chapter.chapter_number = chapterNumber.toFloat()
                    chapter.name = chapter.name.sanitize(manga.title)
                    chapter.scanlator = chapter.scanlator?.ifBlank { null }?.trim()
                }

                val now = Instant.now().epochSecond
                // Used to not set upload date of older chapters
                // to a higher value than newer chapters
                var maxSeenUploadDate = 0L

                val chaptersInDb =
                    transaction {
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga eq mangaId }
                            .map { ChapterTable.toDataClass(it) }
                            .toList()
                    }

                // new chapters after they have been added to the database for auto downloads
                val insertedChapters = mutableListOf<ChapterDataClass>()

                val chaptersToInsert = mutableListOf<ChapterDataClass>() // do not yet have an ID from the database
                val chaptersToUpdate = mutableListOf<ChapterDataClass>()

                uniqueChapters.reversed().forEachIndexed { index, fetchedChapter ->
                    val chapterEntry = chaptersInDb.find { it.url == fetchedChapter.url }

                    val chapterData =
                        ChapterDataClass.fromSChapter(
                            fetchedChapter,
                            chapterEntry?.id ?: 0,
                            index + 1,
                            now,
                            mangaId,
                            runCatching {
                                (source as? HttpSource)?.getChapterUrl(fetchedChapter)
                            }.getOrNull(),
                        )

                    if (chapterEntry == null) {
                        val newChapterData =
                            if (chapterData.uploadDate == 0L) {
                                val altDateUpload = if (maxSeenUploadDate == 0L) now else maxSeenUploadDate
                                chapterData.copy(uploadDate = altDateUpload)
                            } else {
                                maxSeenUploadDate = max(maxSeenUploadDate, chapterData.uploadDate)
                                chapterData
                            }
                        chaptersToInsert.add(newChapterData)
                    } else {
                        val newChapterData =
                            if (chapterData.uploadDate == 0L) {
                                chapterData.copy(uploadDate = chapterEntry.uploadDate)
                            } else {
                                chapterData
                            }
                        chaptersToUpdate.add(newChapterData)
                    }
                }

                val deletedChapterNumbers = TreeSet<Float>()
                val deletedReadChapterNumbers = TreeSet<Float>()
                val deletedBookmarkedChapterNumbers = TreeSet<Float>()
                val deletedDownloadedChapterNumberInfoMap = mutableMapOf<Float, MutableMap<String?, Int>>()
                val deletedChapterNumberDateFetchMap = mutableMapOf<Float, Long>()

                // clear any orphaned/duplicate chapters that are in the db but not in `chapterList`
                val chapterUrls = uniqueChapters.map { it.url }.toSet()

                val chaptersIdsToDelete =
                    chaptersInDb.mapNotNull { dbChapter ->
                        if (!chapterUrls.contains(dbChapter.url)) {
                            if (dbChapter.read) deletedReadChapterNumbers.add(dbChapter.chapterNumber)
                            if (dbChapter.bookmarked) deletedBookmarkedChapterNumbers.add(dbChapter.chapterNumber)
                            if (dbChapter.downloaded) {
                                val pageCountByScanlator =
                                    deletedDownloadedChapterNumberInfoMap.getOrPut(
                                        dbChapter.chapterNumber,
                                    ) { mutableMapOf() }
                                pageCountByScanlator[dbChapter.scanlator] = dbChapter.pageCount
                            }
                            deletedChapterNumbers.add(dbChapter.chapterNumber)
                            deletedChapterNumberDateFetchMap[dbChapter.chapterNumber] = dbChapter.fetchedAt
                            dbChapter.id
                        } else {
                            null
                        }
                    }

                // we got some clean up due
                if (chaptersIdsToDelete.isNotEmpty()) {
                    transaction {
                        PageTable.deleteWhere { PageTable.chapter inList chaptersIdsToDelete }
                        ChapterTable.deleteWhere { ChapterTable.id inList chaptersIdsToDelete }
                    }
                }

                transaction {
                    if (chaptersToInsert.isNotEmpty()) {
                        ChapterTable
                            .batchInsert(chaptersToInsert) { chapter ->
                                this[ChapterTable.url] = chapter.url
                                this[ChapterTable.name] = chapter.name
                                this[ChapterTable.date_upload] = chapter.uploadDate
                                this[ChapterTable.chapter_number] = chapter.chapterNumber
                                this[ChapterTable.scanlator] = chapter.scanlator
                                this[ChapterTable.sourceOrder] = chapter.index
                                this[ChapterTable.fetchedAt] = chapter.fetchedAt
                                this[ChapterTable.manga] = chapter.mangaId
                                this[ChapterTable.realUrl] = chapter.realUrl
                                this[ChapterTable.isRead] = false
                                this[ChapterTable.isBookmarked] = false
                                this[ChapterTable.isDownloaded] = false

                                // is recognized chapter number
                                if (chapter.chapterNumber >= 0f && chapter.chapterNumber in deletedChapterNumbers) {
                                    this[ChapterTable.isRead] = chapter.chapterNumber in deletedReadChapterNumbers
                                    this[ChapterTable.isBookmarked] = chapter.chapterNumber in deletedBookmarkedChapterNumbers

                                    // only preserve download status for chapters of the same scanlator, otherwise,
                                    // the downloaded files won't be found anyway
                                    val downloadedChapterInfo = deletedDownloadedChapterNumberInfoMap[chapter.chapterNumber]
                                    val pageCount = downloadedChapterInfo?.get(chapter.scanlator)
                                    if (pageCount != null) {
                                        this[ChapterTable.isDownloaded] = true
                                        this[ChapterTable.pageCount] = pageCount
                                    }
                                    // Try to use the fetch date of the original entry to not pollute 'Updates' tab
                                    deletedChapterNumberDateFetchMap[chapter.chapterNumber]?.let {
                                        this[ChapterTable.fetchedAt] = it
                                    }
                                }
                            }.forEach { insertedChapters.add(ChapterTable.toDataClass(it)) }
                    }

                    if (chaptersToUpdate.isNotEmpty()) {
                        BatchUpdateStatement(ChapterTable).apply {
                            chaptersToUpdate.forEach {
                                addBatch(EntityID(it.id, ChapterTable))
                                this[ChapterTable.name] = it.name
                                this[ChapterTable.date_upload] = it.uploadDate
                                this[ChapterTable.chapter_number] = it.chapterNumber
                                this[ChapterTable.scanlator] = it.scanlator
                                this[ChapterTable.sourceOrder] = it.index
                                this[ChapterTable.realUrl] = it.realUrl
                            }
                            execute(this@transaction)
                        }
                    }

                    MangaTable.update({ MangaTable.id eq mangaId }) {
                        it[MangaTable.chaptersLastFetchedAt] = Instant.now().epochSecond
                    }
                }

                if (manga.inLibrary) {
                    downloadNewChapters(mangaId, currentLatestChapterNumber, numberOfCurrentChapters, insertedChapters)
                }

                uniqueChapters
            }

        return chapterList
    }

    private fun downloadNewChapters(
        mangaId: Int,
        prevLatestChapterNumber: Float,
        prevNumberOfChapters: Int,
        newChapters: List<ChapterDataClass>,
    ) {
        val log =
            KotlinLogging.logger(
                "${logger.name}::downloadNewChapters(" +
                    "mangaId= $mangaId, " +
                    "prevLatestChapterNumber= $prevLatestChapterNumber, " +
                    "prevNumberOfChapters= $prevNumberOfChapters, " +
                    "newChapters= ${newChapters.size}, " +
                    "autoDownloadNewChaptersLimit= ${serverConfig.autoDownloadNewChaptersLimit.value}, " +
                    "autoDownloadIgnoreReUploads= ${serverConfig.autoDownloadIgnoreReUploads.value}" +
                    ")",
            )

        if (!serverConfig.autoDownloadNewChapters.value) {
            log.debug { "automatic download is not configured" }
            return
        }

        if (newChapters.isEmpty()) {
            log.debug { "no new chapters available" }
            return
        }

        val wasInitialFetch = prevNumberOfChapters == 0
        if (wasInitialFetch) {
            log.debug { "skipping download on initial fetch" }
            return
        }

        if (!Manga.isInIncludedDownloadCategory(log, mangaId)) {
            return
        }

        val unreadChapters = Manga.getUnreadChapters(mangaId).subtract(newChapters.toSet())

        val skipDueToUnreadChapters = serverConfig.excludeEntryWithUnreadChapters.value && unreadChapters.isNotEmpty()
        if (skipDueToUnreadChapters) {
            log.debug { "ignore due to unread chapters" }
            return
        }

        val chapterIdsToDownload = getNewChapterIdsToDownload(newChapters, prevLatestChapterNumber)

        if (chapterIdsToDownload.isEmpty()) {
            log.debug { "no chapters available for download" }
            return
        }

        // The downloader checks if pages are already downloaded to prevent unnecessary downloads. However, in case this
        // is e.g. a re-uploaded chapter, this will prevent the new pages from getting downloaded
        if (serverConfig.autoDownloadDeleteExistingFiles.value) {
            deleteChapters(chapterIdsToDownload)
        }

        log.info { "download ${chapterIdsToDownload.size} new chapter(s)..." }

        DownloadManager.enqueue(EnqueueInput(chapterIdsToDownload))
    }

    private fun getNewChapterIdsToDownload(
        newChapters: List<ChapterDataClass>,
        prevLatestChapterNumber: Float,
    ): List<Int> {
        val reUploadedChapters = newChapters.filter { it.chapterNumber < prevLatestChapterNumber }
        val actualNewChapters = newChapters.subtract(reUploadedChapters.toSet()).toList()
        val chaptersToConsiderForDownloadLimit =
            if (serverConfig.autoDownloadIgnoreReUploads.value) {
                if (actualNewChapters.isNotEmpty()) actualNewChapters.removeDuplicates(actualNewChapters[0]) else emptyList()
            } else {
                newChapters.removeDuplicates(newChapters[0])
            }.sortedBy { it.index }

        val latestChapterToDownloadIndex =
            if (serverConfig.autoDownloadNewChaptersLimit.value == 0) {
                chaptersToConsiderForDownloadLimit.size
            } else {
                serverConfig.autoDownloadNewChaptersLimit.value.coerceIn(0, chaptersToConsiderForDownloadLimit.size)
            }
        val limitedChaptersToDownload = chaptersToConsiderForDownloadLimit.subList(0, latestChapterToDownloadIndex)
        val limitedChaptersToDownloadWithDuplicates =
            (
                limitedChaptersToDownload +
                    newChapters.filter { newChapter ->
                        limitedChaptersToDownload.find { it.chapterNumber == newChapter.chapterNumber } != null
                    }
            ).toSet()

        return limitedChaptersToDownloadWithDuplicates.map { it.id }
    }

    fun modifyChapter(
        mangaId: Int,
        chapterIndex: Int,
        isRead: Boolean?,
        isBookmarked: Boolean?,
        markPrevRead: Boolean?,
        lastPageRead: Int?,
    ) {
        transaction {
            if (listOf(isRead, isBookmarked, lastPageRead).any { it != null }) {
                ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }) { update ->
                    isRead?.also {
                        update[ChapterTable.isRead] = it
                    }
                    isBookmarked?.also {
                        update[ChapterTable.isBookmarked] = it
                    }
                    lastPageRead?.also {
                        update[ChapterTable.lastPageRead] = it
                        update[ChapterTable.lastReadAt] = Instant.now().epochSecond
                    }
                }
            }

            markPrevRead?.let {
                ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder less chapterIndex) }) {
                    it[ChapterTable.isRead] = markPrevRead
                }
            }
        }

        if (isRead == true || markPrevRead == true) {
            Track.asyncTrackChapter(setOf(mangaId))
        }
    }

    @Serializable
    data class ChapterChange(
        val isRead: Boolean? = null,
        val isBookmarked: Boolean? = null,
        val lastPageRead: Int? = null,
        val delete: Boolean? = null,
    )

    @Serializable
    data class MangaChapterBatchEditInput(
        val chapterIds: List<Int>? = null,
        val chapterIndexes: List<Int>? = null,
        val change: ChapterChange?,
    )

    @Serializable
    data class ChapterBatchEditInput(
        val chapterIds: List<Int>? = null,
        val change: ChapterChange?,
    )

    fun modifyChapters(
        input: MangaChapterBatchEditInput,
        mangaId: Int? = null,
    ) {
        // Make sure change is defined
        if (input.change == null) return
        val (isRead, isBookmarked, lastPageRead, delete) = input.change

        // Handle deleting separately
        if (delete == true) {
            deleteChapters(input, mangaId)
        }

        // return early if there are no other changes
        if (listOfNotNull(isRead, isBookmarked, lastPageRead).isEmpty()) return

        // Make sure some filter is defined
        val condition =
            when {
                mangaId != null ->
                    // mangaId is not null, scope query under manga
                    when {
                        input.chapterIds != null ->
                            Op.build { (ChapterTable.manga eq mangaId) and (ChapterTable.id inList input.chapterIds) }

                        input.chapterIndexes != null ->
                            Op.build { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder inList input.chapterIndexes) }

                        else -> null
                    }

                else -> {
                    // mangaId is null, only chapterIndexes is valid for this case
                    when {
                        input.chapterIds != null ->
                            Op.build { (ChapterTable.id inList input.chapterIds) }

                        else -> null
                    }
                }
            } ?: return

        transaction {
            val now = Instant.now().epochSecond
            ChapterTable.update({ condition }) { update ->
                isRead?.also {
                    update[ChapterTable.isRead] = it
                }
                isBookmarked?.also {
                    update[ChapterTable.isBookmarked] = it
                }
                lastPageRead?.also {
                    update[ChapterTable.lastPageRead] = it
                    update[ChapterTable.lastReadAt] = now
                }
            }
        }

        if (isRead == true) {
            val mangaIds =
                transaction {
                    ChapterTable
                        .selectAll()
                        .where(condition)
                        .map { it[ChapterTable.manga].value }
                        .toSet()
                }
            Track.asyncTrackChapter(mangaIds)
        }
    }

    fun getChaptersMetaMaps(chapterIds: List<EntityID<Int>>): Map<EntityID<Int>, Map<String, String>> =
        transaction {
            ChapterMetaTable
                .selectAll()
                .where { ChapterMetaTable.ref inList chapterIds }
                .groupBy { it[ChapterMetaTable.ref] }
                .mapValues { it.value.associate { it[ChapterMetaTable.key] to it[ChapterMetaTable.value] } }
                .withDefault { emptyMap<String, String>() }
        }

    fun getChapterMetaMap(chapter: EntityID<Int>): Map<String, String> =
        transaction {
            ChapterMetaTable
                .selectAll()
                .where { ChapterMetaTable.ref eq chapter }
                .associate { it[ChapterMetaTable.key] to it[ChapterMetaTable.value] }
        }

    fun modifyChapterMeta(
        mangaId: Int,
        chapterIndex: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val chapterId =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }
                    .first()[ChapterTable.id]
                    .value
            modifyChapterMeta(chapterId, key, value)
        }
    }

    fun modifyChapterMeta(
        chapterId: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val meta =
                ChapterMetaTable
                    .selectAll()
                    .where { (ChapterMetaTable.ref eq chapterId) and (ChapterMetaTable.key eq key) }
                    .firstOrNull()

            if (meta == null) {
                ChapterMetaTable.insert {
                    it[ChapterMetaTable.key] = key
                    it[ChapterMetaTable.value] = value
                    it[ChapterMetaTable.ref] = chapterId
                }
            } else {
                ChapterMetaTable.update({ (ChapterMetaTable.ref eq chapterId) and (ChapterMetaTable.key eq key) }) {
                    it[ChapterMetaTable.value] = value
                }
            }
        }
    }

    fun deleteChapter(
        mangaId: Int,
        chapterIndex: Int,
    ) {
        transaction {
            val chapterId =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }
                    .first()[ChapterTable.id]
                    .value

            ChapterDownloadHelper.delete(mangaId, chapterId)

            ChapterTable.update({ (ChapterTable.manga eq mangaId) and (ChapterTable.sourceOrder eq chapterIndex) }) {
                it[isDownloaded] = false
            }
        }
    }

    private fun deleteChapters(
        input: MangaChapterBatchEditInput,
        mangaId: Int? = null,
    ) {
        if (input.chapterIds != null) {
            deleteChapters(input.chapterIds)
        } else if (input.chapterIndexes != null && mangaId != null) {
            transaction {
                val chapterIds =
                    ChapterTable
                        .select(ChapterTable.manga, ChapterTable.id)
                        .where {
                            (ChapterTable.sourceOrder inList input.chapterIndexes) and
                                (ChapterTable.manga eq mangaId)
                        }.map { row ->
                            val chapterId = row[ChapterTable.id].value
                            ChapterDownloadHelper.delete(mangaId, chapterId)

                            chapterId
                        }

                ChapterTable.update({ ChapterTable.id inList chapterIds }) {
                    it[isDownloaded] = false
                }
            }
        }
    }

    fun deleteChapters(chapterIds: List<Int>) {
        transaction {
            ChapterTable
                .select(ChapterTable.manga, ChapterTable.id)
                .where { ChapterTable.id inList chapterIds }
                .forEach { row ->
                    val chapterMangaId = row[ChapterTable.manga].value
                    val chapterId = row[ChapterTable.id].value
                    ChapterDownloadHelper.delete(chapterMangaId, chapterId)
                }

            ChapterTable.update({ ChapterTable.id inList chapterIds }) {
                it[isDownloaded] = false
            }
        }
    }

    fun getRecentChapters(pageNum: Int): PaginatedList<MangaChapterDataClass> =
        paginatedFrom(pageNum) {
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where { (MangaTable.inLibrary eq true) and (ChapterTable.fetchedAt greater MangaTable.inLibraryAt) }
                    .orderBy(ChapterTable.fetchedAt to SortOrder.DESC)
                    .map {
                        MangaChapterDataClass(
                            MangaTable.toDataClass(it),
                            ChapterTable.toDataClass(it),
                        )
                    }
            }
        }
}
