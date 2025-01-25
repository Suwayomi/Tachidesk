/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.types

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.ResultRow
import suwayomi.tachidesk.graphql.cache.CustomCacheMap
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.impl.MangaList
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.dataclass.toGenreList
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import java.time.Instant
import java.util.concurrent.CompletableFuture

class MangaType(
    val id: Int,
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val thumbnailUrlLastFetched: Long?,
    val initialized: Boolean,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genre: List<String>,
    val status: MangaStatus,
    val inLibrary: Boolean,
    val inLibraryAt: Long,
    val updateStrategy: UpdateStrategy,
    val realUrl: String?,
    var lastFetchedAt: Long?, // todo
    var chaptersLastFetchedAt: Long?, // todo
) : Node {
    companion object {
        fun clearCacheFor(
            mangaIds: List<Int>,
            dataFetchingEnvironment: DataFetchingEnvironment,
        ) {
            mangaIds.forEach { clearCacheFor(it, dataFetchingEnvironment) }
        }

        fun clearCacheFor(
            mangaId: Int,
            dataFetchingEnvironment: DataFetchingEnvironment,
        ) {
            dataFetchingEnvironment.getDataLoader<Int, MangaType>("MangaDataLoader")?.clear(mangaId)

            val mangaForIdsDataLoader =
                dataFetchingEnvironment.getDataLoader<List<Int>, MangaNodeList>("MangaForIdsDataLoader")
            @Suppress("UNCHECKED_CAST")
            (mangaForIdsDataLoader?.cacheMap as? CustomCacheMap<List<Int>, MangaNodeList>?)
                ?.getKeys()
                ?.filter { it.contains(mangaId) }
                ?.forEach { mangaForIdsDataLoader.clear(it) }

            dataFetchingEnvironment.getDataLoader<Int, Int>("DownloadedChapterCountForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, Int>("UnreadChapterCountForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, Int>("BookmarkedChapterCountForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, Int>("HasDuplicateChaptersForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("LastReadChapterForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("LatestReadChapterForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("LatestFetchedChapterForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("LatestUploadedChapterForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, ChapterType>("FirstUnreadChapterForMangaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment
                .getDataLoader<Int, ChapterNodeList>(
                    "ChaptersForMangaDataLoader",
                )?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, List<MangaMetaType>>("MangaMetaDataLoader")?.clear(mangaId)
            dataFetchingEnvironment.getDataLoader<Int, CategoryNodeList>("CategoriesForMangaDataLoader")?.clear(mangaId)
        }
    }

    constructor(row: ResultRow) : this(
        row[MangaTable.id].value,
        row[MangaTable.sourceReference],
        row[MangaTable.url],
        row[MangaTable.title],
        row[MangaTable.thumbnail_url]?.let { MangaList.proxyThumbnailUrl(row[MangaTable.id].value) },
        row[MangaTable.thumbnailUrlLastFetched],
        row[MangaTable.initialized],
        row[MangaTable.artist],
        row[MangaTable.author],
        row[MangaTable.description],
        row[MangaTable.genre].toGenreList(),
        MangaStatus.valueOf(row[MangaTable.status]),
        row.getOrNull(MangaUserTable.inLibrary) ?: false,
        row.getOrNull(MangaUserTable.inLibraryAt) ?: 0,
        UpdateStrategy.valueOf(row[MangaTable.updateStrategy]),
        row[MangaTable.realUrl],
        row[MangaTable.lastFetchedAt],
        row[MangaTable.chaptersLastFetchedAt],
    )

    constructor(dataClass: MangaDataClass) : this(
        dataClass.id,
        dataClass.sourceId.toLong(),
        dataClass.url,
        dataClass.title,
        dataClass.thumbnailUrl,
        dataClass.thumbnailUrlLastFetched,
        dataClass.initialized,
        dataClass.artist,
        dataClass.author,
        dataClass.description,
        dataClass.genre,
        MangaStatus.valueOf(dataClass.status),
        dataClass.inLibrary,
        dataClass.inLibraryAt,
        dataClass.updateStrategy,
        dataClass.realUrl,
        dataClass.lastFetchedAt,
        dataClass.chaptersLastFetchedAt,
    )

    fun downloadCount(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<Int> =
        dataFetchingEnvironment.getValueFromDataLoader("DownloadedChapterCountForMangaDataLoader", id)

    fun unreadCount(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<Int> =
        dataFetchingEnvironment.getValueFromDataLoader("UnreadChapterCountForMangaDataLoader", id)

    fun bookmarkCount(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<Int> =
        dataFetchingEnvironment.getValueFromDataLoader("BookmarkedChapterCountForMangaDataLoader", id)

    fun hasDuplicateChapters(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<Boolean> =
        dataFetchingEnvironment.getValueFromDataLoader("HasDuplicateChaptersForMangaDataLoader", id)

    fun lastReadChapter(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterType?> =
        dataFetchingEnvironment.getValueFromDataLoader("LastReadChapterForMangaDataLoader", id)

    fun latestReadChapter(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterType?> =
        dataFetchingEnvironment.getValueFromDataLoader("LatestReadChapterForMangaDataLoader", id)

    fun latestFetchedChapter(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterType?> =
        dataFetchingEnvironment.getValueFromDataLoader("LatestFetchedChapterForMangaDataLoader", id)

    fun latestUploadedChapter(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterType?> =
        dataFetchingEnvironment.getValueFromDataLoader("LatestUploadedChapterForMangaDataLoader", id)

    fun firstUnreadChapter(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterType?> =
        dataFetchingEnvironment.getValueFromDataLoader("FirstUnreadChapterForMangaDataLoader", id)

    fun chapters(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<ChapterNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, ChapterNodeList>("ChaptersForMangaDataLoader", id)

    fun age(): Long? {
        if (lastFetchedAt == null) return null
        return Instant.now().epochSecond.minus(lastFetchedAt!!)
    }

    fun chaptersAge(): Long? {
        if (chaptersLastFetchedAt == null) return null

        return Instant.now().epochSecond.minus(chaptersLastFetchedAt!!)
    }

    fun meta(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<List<MangaMetaType>> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, List<MangaMetaType>>("MangaMetaDataLoader", id)

    fun categories(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<CategoryNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, CategoryNodeList>("CategoriesForMangaDataLoader", id)

    fun source(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<SourceType?> =
        dataFetchingEnvironment.getValueFromDataLoader<Long, SourceType?>("SourceDataLoader", sourceId)

    fun trackRecords(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<TrackRecordNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, TrackRecordNodeList>("TrackRecordsForMangaIdDataLoader", id)
}

data class MangaNodeList(
    override val nodes: List<MangaType>,
    override val edges: List<MangaEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class MangaEdge(
        override val cursor: Cursor,
        override val node: MangaType,
    ) : Edge()

    companion object {
        fun List<MangaType>.toNodeList(): MangaNodeList =
            MangaNodeList(
                nodes = this,
                edges = getEdges(),
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = Cursor(0.toString()),
                        endCursor = Cursor(lastIndex.toString()),
                    ),
                totalCount = size,
            )

        private fun List<MangaType>.getEdges(): List<MangaEdge> {
            if (isEmpty()) return emptyList()
            return listOf(
                MangaEdge(
                    cursor = Cursor("0"),
                    node = first(),
                ),
                MangaEdge(
                    cursor = Cursor(lastIndex.toString()),
                    node = last(),
                ),
            )
        }
    }
}
