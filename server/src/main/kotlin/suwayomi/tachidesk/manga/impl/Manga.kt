package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.HttpStatus
import okhttp3.CacheControl
import okhttp3.Response
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.MangaList.proxyThumbnailUrl
import suwayomi.tachidesk.manga.impl.Source.getSource
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.MissingThumbnailException
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.util.lang.isNotEmpty
import suwayomi.tachidesk.manga.impl.util.network.await
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrNull
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.clearCachedImage
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.getImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageUtil
import suwayomi.tachidesk.manga.impl.util.updateMangaDownloadDir
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.dataclass.toGenreList
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant

private val logger = KotlinLogging.logger { }

object Manga {
    private fun truncate(
        text: String?,
        maxLength: Int,
    ): String? =
        if (text?.length ?: 0 > maxLength) {
            text?.take(maxLength - 3) + "..."
        } else {
            text
        }

    suspend fun getManga(
        userId: Int,
        mangaId: Int,
        onlineFetch: Boolean = false,
    ): MangaDataClass {
        var mangaEntry =
            transaction {
                MangaTable
                    .getWithUserData(userId)
                    .selectAll()
                    .where { MangaTable.id eq mangaId }
                    .first()
            }

        return if (!onlineFetch && mangaEntry[MangaTable.initialized]) {
            getMangaDataClass(userId, mangaId, mangaEntry)
        } else { // initialize manga
            val sManga = fetchManga(mangaId) ?: return getMangaDataClass(userId, mangaId, mangaEntry)

            mangaEntry =
                transaction {
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id eq mangaId }
                        .first()
                }

            MangaDataClass(
                id = mangaId,
                sourceId = mangaEntry[MangaTable.sourceReference].toString(),
                url = mangaEntry[MangaTable.url],
                title = mangaEntry[MangaTable.title],
                thumbnailUrl = proxyThumbnailUrl(mangaId),
                thumbnailUrlLastFetched = mangaEntry[MangaTable.thumbnailUrlLastFetched],
                initialized = true,
                artist = sManga.artist,
                author = sManga.author,
                description = sManga.description,
                genre = sManga.genre.toGenreList(),
                status = MangaStatus.valueOf(sManga.status).name,
                inLibrary = mangaEntry.getOrNull(MangaUserTable.inLibrary) ?: false,
                inLibraryAt = mangaEntry.getOrNull(MangaUserTable.inLibraryAt) ?: 0,
                source = getSource(mangaEntry[MangaTable.sourceReference]),
                meta = getMangaMetaMap(userId, mangaId),
                realUrl = mangaEntry[MangaTable.realUrl],
                lastFetchedAt = mangaEntry[MangaTable.lastFetchedAt],
                chaptersLastFetchedAt = mangaEntry[MangaTable.chaptersLastFetchedAt],
                updateStrategy = UpdateStrategy.valueOf(mangaEntry[MangaTable.updateStrategy]),
                freshData = true,
                trackers = Track.getTrackRecordsByMangaId(userId, mangaId),
            )
        }
    }

    suspend fun fetchManga(mangaId: Int): SManga? {
        val mangaEntry = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }

        val source =
            getCatalogueSourceOrNull(mangaEntry[MangaTable.sourceReference])
                ?: return null
        val sManga =
            source.getMangaDetails(
                SManga.create().apply {
                    url = mangaEntry[MangaTable.url]
                    title = mangaEntry[MangaTable.title]
                    thumbnail_url = mangaEntry[MangaTable.thumbnail_url]
                    artist = mangaEntry[MangaTable.artist]
                    author = mangaEntry[MangaTable.author]
                    description = mangaEntry[MangaTable.description]
                    genre = mangaEntry[MangaTable.genre]
                    status = mangaEntry[MangaTable.status]
                    update_strategy = UpdateStrategy.valueOf(mangaEntry[MangaTable.updateStrategy])
                },
            )

        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) {
                val remoteTitle =
                    try {
                        sManga.title
                    } catch (_: UninitializedPropertyAccessException) {
                        ""
                    }
                if (remoteTitle.isNotEmpty() && remoteTitle != mangaEntry[MangaTable.title]) {
                    val canUpdateTitle = updateMangaDownloadDir(mangaId, remoteTitle)

                    if (canUpdateTitle) {
                        it[MangaTable.title] = remoteTitle
                    }
                }
                it[MangaTable.initialized] = true

                it[MangaTable.artist] = sManga.artist ?: mangaEntry[MangaTable.artist]
                it[MangaTable.author] = sManga.author ?: mangaEntry[MangaTable.author]
                it[MangaTable.description] = sManga.description?.let { truncate(it, 4096) }
                    ?: mangaEntry[MangaTable.description]
                it[MangaTable.genre] = sManga.genre ?: mangaEntry[MangaTable.genre]
                it[MangaTable.status] = sManga.status
                if (!sManga.thumbnail_url.isNullOrEmpty() && sManga.thumbnail_url != mangaEntry[MangaTable.thumbnail_url]) {
                    it[MangaTable.thumbnail_url] = sManga.thumbnail_url
                    it[MangaTable.thumbnailUrlLastFetched] = Instant.now().epochSecond
                    clearThumbnail(mangaId)
                }

                it[MangaTable.realUrl] =
                    runCatching {
                        (source as? HttpSource)?.getMangaUrl(
                            SManga.create().apply {
                                url = mangaEntry[MangaTable.url]
                                title = remoteTitle.ifEmpty { mangaEntry[MangaTable.title] }
                                thumbnail_url = mangaEntry[MangaTable.thumbnail_url]
                                artist = sManga.artist ?: mangaEntry[MangaTable.artist]
                                author = sManga.author ?: mangaEntry[MangaTable.author]
                                description = sManga.description ?: mangaEntry[MangaTable.description]
                                genre = sManga.genre ?: mangaEntry[MangaTable.genre]
                                status = sManga.status
                                update_strategy = sManga.update_strategy
                            },
                        )
                    }.getOrNull()

                it[MangaTable.lastFetchedAt] = Instant.now().epochSecond

                it[MangaTable.updateStrategy] = sManga.update_strategy.name
            }
        }

        return sManga
    }

    suspend fun getMangaFull(
        userId: Int,
        mangaId: Int,
        onlineFetch: Boolean = false,
    ): MangaDataClass {
        val mangaDaaClass = getManga(userId, mangaId, onlineFetch)

        return transaction {
            val unreadCount =
                ChapterTable
                    .getWithUserData(userId)
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterUserTable.isRead eq false) }
                    .count()

            val downloadCount =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) and (ChapterTable.isDownloaded eq true) }
                    .count()

            val chapterCount =
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) }
                    .count()

            val lastChapterRead =
                ChapterTable
                    .getWithUserData(userId)
                    .selectAll()
                    .where { (ChapterTable.manga eq mangaId) }
                    .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                    .firstOrNull { it.getOrNull(ChapterUserTable.isRead) == true }

            mangaDaaClass.unreadCount = unreadCount
            mangaDaaClass.downloadCount = downloadCount
            mangaDaaClass.chapterCount = chapterCount
            mangaDaaClass.lastChapterRead = lastChapterRead?.let { ChapterTable.toDataClass(userId, it) }

            mangaDaaClass
        }
    }

    private fun getMangaDataClass(
        userId: Int,
        mangaId: Int,
        mangaEntry: ResultRow,
    ) = MangaDataClass(
        id = mangaId,
        sourceId = mangaEntry[MangaTable.sourceReference].toString(),
        url = mangaEntry[MangaTable.url],
        title = mangaEntry[MangaTable.title],
        thumbnailUrl = proxyThumbnailUrl(mangaId),
        thumbnailUrlLastFetched = mangaEntry[MangaTable.thumbnailUrlLastFetched],
        initialized = true,
        artist = mangaEntry[MangaTable.artist],
        author = mangaEntry[MangaTable.author],
        description = mangaEntry[MangaTable.description],
        genre = mangaEntry[MangaTable.genre].toGenreList(),
        status = MangaStatus.valueOf(mangaEntry[MangaTable.status]).name,
        inLibrary = mangaEntry.getOrNull(MangaUserTable.inLibrary) ?: false,
        inLibraryAt = mangaEntry.getOrNull(MangaUserTable.inLibraryAt) ?: 0,
        source = getSource(mangaEntry[MangaTable.sourceReference]),
        meta = getMangaMetaMap(userId, mangaId),
        realUrl = mangaEntry[MangaTable.realUrl],
        lastFetchedAt = mangaEntry[MangaTable.lastFetchedAt],
        chaptersLastFetchedAt = mangaEntry[MangaTable.chaptersLastFetchedAt],
        updateStrategy = UpdateStrategy.valueOf(mangaEntry[MangaTable.updateStrategy]),
        freshData = false,
        trackers = Track.getTrackRecordsByMangaId(userId, mangaId),
    )

    fun getMangaMetaMap(
        userId: Int,
        mangaId: Int,
    ): Map<String, String> =
        transaction {
            MangaMetaTable
                .selectAll()
                .where { MangaMetaTable.user eq userId and (MangaMetaTable.ref eq mangaId) }
                .associate { it[MangaMetaTable.key] to it[MangaMetaTable.value] }
        }

    fun modifyMangaMeta(
        userId: Int,
        mangaId: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val meta =
                MangaMetaTable
                    .selectAll()
                    .where { MangaMetaTable.user eq userId and (MangaMetaTable.ref eq mangaId) and (MangaMetaTable.key eq key) }
                    .firstOrNull()

            if (meta == null) {
                MangaMetaTable.insert {
                    it[MangaMetaTable.key] = key
                    it[MangaMetaTable.value] = value
                    it[MangaMetaTable.ref] = mangaId
                    it[MangaMetaTable.user] = userId
                }
            } else {
                MangaMetaTable.update(
                    {
                        MangaMetaTable.user eq userId and
                            (MangaMetaTable.ref eq mangaId) and
                            (MangaMetaTable.key eq key)
                    },
                ) {
                    it[MangaMetaTable.value] = value
                }
            }
        }
    }

    private suspend fun fetchThumbnailUrl(mangaId: Int): String? {
        getManga(0, mangaId, true)
        return transaction {
            MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
        }[MangaTable.thumbnail_url]
    }

    private val applicationDirs: ApplicationDirs by injectLazy()
    private val network: NetworkHelper by injectLazy()

    private suspend fun fetchHttpSourceMangaThumbnail(
        source: HttpSource,
        mangaEntry: ResultRow,
        refreshUrl: Boolean = false,
    ): Response {
        val mangaId = mangaEntry[MangaTable.id].value

        val requiresInitialization = mangaEntry[MangaTable.thumbnail_url] == null && !mangaEntry[MangaTable.initialized]
        val refreshThumbnailUrl = refreshUrl || requiresInitialization

        val thumbnailUrl =
            if (refreshThumbnailUrl) {
                fetchThumbnailUrl(mangaId)
            } else {
                mangaEntry[MangaTable.thumbnail_url]
            } ?: throw NullPointerException("No thumbnail found")

        return try {
            source.client
                .newCall(
                    GET(thumbnailUrl, source.headers, cache = CacheControl.FORCE_NETWORK),
                ).awaitSuccess()
        } catch (e: HttpException) {
            val tryToRefreshUrl =
                !refreshUrl &&
                    listOf(
                        HttpStatus.GONE.code,
                        HttpStatus.MOVED_PERMANENTLY.code,
                        HttpStatus.NOT_FOUND.code,
                        523, // (Cloudflare) Origin Is Unreachable
                        522, // (Cloudflare) Connection timed out
                    ).contains(e.code)
            if (!tryToRefreshUrl) {
                throw e
            }

            fetchHttpSourceMangaThumbnail(source, mangaEntry, refreshUrl = true)
        }
    }

    suspend fun fetchMangaThumbnail(mangaId: Int): Pair<InputStream, String> {
        val cacheSaveDir = applicationDirs.tempThumbnailCacheRoot
        val fileName = mangaId.toString()

        val mangaEntry = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }
        val sourceId = mangaEntry[MangaTable.sourceReference]

        return when (val source = getCatalogueSourceOrStub(sourceId)) {
            is HttpSource ->
                getImageResponse(cacheSaveDir, fileName) {
                    fetchHttpSourceMangaThumbnail(source, mangaEntry)
                }

            is LocalSource -> {
                val imageFile =
                    mangaEntry[MangaTable.thumbnail_url]?.let {
                        val file = File(it)
                        if (file.exists()) {
                            file
                        } else {
                            null
                        }
                    } ?: throw IOException("Thumbnail does not exist")
                val contentType =
                    ImageUtil.findImageType { imageFile.inputStream() }?.mime
                        ?: "image/jpeg"
                imageFile.inputStream() to contentType
            }

            is StubSource ->
                getImageResponse(cacheSaveDir, fileName) {
                    val thumbnailUrl =
                        mangaEntry[MangaTable.thumbnail_url]
                            ?: throw NullPointerException("No thumbnail found")
                    network.client
                        .newCall(
                            GET(thumbnailUrl, cache = CacheControl.FORCE_NETWORK),
                        ).await()
                }

            else -> throw IllegalArgumentException("Unknown source")
        }
    }

    suspend fun getMangaThumbnail(mangaId: Int): Pair<InputStream, String> {
        val mangaInLibrary =
            transaction {
                MangaUserTable
                    .selectAll()
                    .where {
                        MangaUserTable.manga eq mangaId and (MangaUserTable.inLibrary eq true)
                    }.isNotEmpty()
            }
        val mangaSource =
            transaction {
                MangaTable
                    .select(MangaTable.sourceReference)
                    .where { MangaTable.id eq mangaId }
                    .firstOrNull()
                    ?.get(MangaTable.sourceReference)
            }

        if (mangaInLibrary && mangaSource != LocalSource.ID) {
            return try {
                ThumbnailDownloadHelper.getImage(mangaId)
            } catch (_: MissingThumbnailException) {
                ThumbnailDownloadHelper.download(mangaId)
                ThumbnailDownloadHelper.getImage(mangaId)
            }
        }

        return fetchMangaThumbnail(mangaId)
    }

    fun clearThumbnail(mangaId: Int) {
        val fileName = mangaId.toString()

        clearCachedImage(applicationDirs.tempThumbnailCacheRoot, fileName)
        clearCachedImage(applicationDirs.thumbnailDownloadsRoot, fileName)
    }

    fun getLatestChapter(
        userId: Int,
        mangaId: Int,
    ): ChapterDataClass? =
        transaction {
            ChapterTable
                .getWithUserData(
                    userId,
                ).selectAll()
                .where { ChapterTable.manga eq mangaId }
                .maxByOrNull { it[ChapterTable.sourceOrder] }
        }?.let { ChapterTable.toDataClass(userId, it) }

    fun getUnreadChapters(
        userId: Int,
        mangaId: Int,
    ): List<ChapterDataClass> =
        transaction {
            ChapterTable
                .getWithUserData(userId)
                .selectAll()
                .where { (ChapterTable.manga eq mangaId) and (ChapterUserTable.isRead eq false) }
                .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                .map { ChapterTable.toDataClass(userId, it) }
        }

    fun isInIncludedDownloadCategory(
        userId: Int,
        logContext: KLogger = logger,
        mangaId: Int,
    ): Boolean {
        val log = KotlinLogging.logger("${logContext.name}::isInExcludedDownloadCategory($mangaId)")

        // Verify the manga is configured to be downloaded based on it's categories.
        var mangaCategories = CategoryManga.getMangaCategories(userId, mangaId).toSet()
        // if the manga has no categories, then it's implicitly in the default category
        if (mangaCategories.isEmpty()) {
            val defaultCategory = Category.getCategoryById(userId, Category.DEFAULT_CATEGORY_ID)!!
            mangaCategories = setOf(defaultCategory)
        }

        val downloadCategoriesMap = Category.getCategoryList(userId).groupBy { it.includeInDownload }
        val unsetCategories = downloadCategoriesMap[IncludeOrExclude.UNSET].orEmpty()
        // We only download if it's in the include list, and not in the exclude list.
        // Use the unset categories as the included categories if the included categories is
        // empty
        val includedCategories = downloadCategoriesMap[IncludeOrExclude.INCLUDE].orEmpty().ifEmpty { unsetCategories }
        val excludedCategories = downloadCategoriesMap[IncludeOrExclude.EXCLUDE].orEmpty()
        // Only download manga that aren't in any excluded categories
        val mangaExcludeCategories = mangaCategories.intersect(excludedCategories.toSet())
        if (mangaExcludeCategories.isNotEmpty()) {
            log.debug { "download excluded by categories: '${mangaExcludeCategories.joinToString("', '") { it.name }}'" }
            return false
        }
        val mangaDownloadCategories = mangaCategories.intersect(includedCategories.toSet())
        if (mangaDownloadCategories.isNotEmpty()) {
            log.debug { "download inluded by categories: '${mangaDownloadCategories.joinToString("', '") { it.name }}'" }
        } else {
            log.debug { "skipping download due to download categories configuration" }
            return false
        }

        return true
    }
}
