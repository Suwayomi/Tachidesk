package suwayomi.tachidesk.manga.impl.chapter

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.PageTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.toDataClass

suspend fun getChapterDownloadReady(
    userId: Int,
    chapterId: Int? = null,
    chapterIndex: Int? = null,
    mangaId: Int? = null,
): ChapterDataClass {
    val chapter = ChapterForDownload(userId, chapterId, chapterIndex, mangaId)

    return chapter.asDownloadReady()
}

suspend fun getChapterDownloadReadyById(
    userId: Int,
    chapterId: Int,
): ChapterDataClass = getChapterDownloadReady(userId = userId, chapterId = chapterId)

suspend fun getChapterDownloadReadyByIndex(
    userId: Int,
    chapterIndex: Int,
    mangaId: Int,
): ChapterDataClass = getChapterDownloadReady(userId = userId, chapterIndex = chapterIndex, mangaId = mangaId)

private class ChapterForDownload(
    private val userId: Int,
    optChapterId: Int? = null,
    optChapterIndex: Int? = null,
    optMangaId: Int? = null,
) {
    var chapterEntry: ResultRow
    val chapterId: Int
    val chapterIndex: Int
    val mangaId: Int

    val logger: KLogger

    suspend fun asDownloadReady(): ChapterDataClass {
        val log = KotlinLogging.logger("${logger.name}::asDownloadReady")

        val isMarkedAsDownloaded = chapterEntry[ChapterTable.isDownloaded]
        val doesFirstPageExist = firstPageExists()
        val isDownloaded = isMarkedAsDownloaded && doesFirstPageExist

        log.debug { "isDownloaded= $isDownloaded (isMarkedAsDownloaded= $isMarkedAsDownloaded, doesFirstPageExist= $doesFirstPageExist)" }

        if (!isDownloaded) {
            log.debug { "reset download status and fetch page list" }

            markAsNotDownloaded()

            val pageList = fetchPageList()

            updateDatabasePages(pageList)
        }

        return asDataClass()
    }

    private fun asDataClass() = ChapterTable.toDataClass(userId, chapterEntry) // no need for user id

    init {
        chapterEntry = freshChapterEntry(optChapterId, optChapterIndex, optMangaId)
        chapterId = chapterEntry[ChapterTable.id].value
        chapterIndex = chapterEntry[ChapterTable.sourceOrder]
        mangaId = chapterEntry[ChapterTable.manga].value

        logger =
            KotlinLogging.logger(
                "${ChapterForDownload::class.java.name}(mangaId= $mangaId, chapterId= $chapterId, chapterIndex= $chapterIndex)",
            )
    }

    private fun freshChapterEntry(
        optChapterId: Int? = null,
        optChapterIndex: Int? = null,
        optMangaId: Int? = null,
    ) = transaction {
        ChapterTable
            .getWithUserData(userId)
            .selectAll()
            .where {
                if (optChapterId != null) {
                    ChapterTable.id eq optChapterId
                } else if (optChapterIndex != null && optMangaId != null) {
                    (ChapterTable.sourceOrder eq optChapterIndex) and (ChapterTable.manga eq optMangaId)
                } else {
                    throw Exception("'optChapterId' or 'optChapterIndex' and 'optMangaId' have to be passed")
                }
            }.first()
    }

    private suspend fun fetchPageList(): List<Page> {
        val mangaEntry = transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.first() }
        val source = getCatalogueSourceOrStub(mangaEntry[MangaTable.sourceReference])

        return source.getPageList(
            SChapter.create().apply {
                url = chapterEntry[ChapterTable.url]
                name = chapterEntry[ChapterTable.name]
                scanlator = chapterEntry[ChapterTable.scanlator]
                chapter_number = chapterEntry[ChapterTable.chapter_number]
                date_upload = chapterEntry[ChapterTable.date_upload]
            },
        )
    }

    private fun markAsNotDownloaded() {
        // chapter may be downloaded but if we are here, then images might be deleted and database data be false
        transaction {
            ChapterTable.update({ (ChapterTable.sourceOrder eq chapterIndex) and (ChapterTable.manga eq mangaId) }) {
                it[isDownloaded] = false
            }
        }
    }

    private fun updateDatabasePages(pageList: List<Page>) {
        transaction {
            PageTable.deleteWhere { PageTable.chapter eq chapterId }
            PageTable.batchInsert(pageList) { page ->
                this[PageTable.index] = page.index
                this[PageTable.url] = page.url
                this[PageTable.imageUrl] = page.imageUrl
                this[PageTable.chapter] = chapterId
            }
        }

        updatePageCount(pageList, chapterId)

        // chapter was updated
        chapterEntry = freshChapterEntry(chapterId, chapterIndex, mangaId)
    }

    private fun updatePageCount(
        pageList: List<Page>,
        chapterId: Int,
    ) {
        transaction {
            ChapterTable.update({ ChapterTable.id eq chapterId }) {
                val pageCount = pageList.size
                it[ChapterTable.pageCount] = pageCount
            }
            val pageCount = pageList.size
            ChapterUserTable
                .selectAll()
                .where {
                    ChapterUserTable.chapter eq chapterId and (ChapterUserTable.lastPageRead greaterEq pageCount)
                }.forEach { row ->
                    ChapterUserTable.update({ ChapterUserTable.id eq row[ChapterUserTable.id] }) {
                        it[ChapterUserTable.lastPageRead] = pageCount - 1
                    }
                }
        }
    }

    private fun firstPageExists(): Boolean =
        try {
            ChapterDownloadHelper.getImage(mangaId, chapterId, 0).first.close()
            true
        } catch (e: Exception) {
            false
        }
}
