package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.MangasPage
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpdateStatement
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import suwayomi.tachidesk.manga.model.dataclass.PagedMangaListDataClass
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.time.Instant

object MangaList {
    fun proxyThumbnailUrl(mangaId: Int): String = "/api/v1/manga/$mangaId/thumbnail"

    suspend fun getMangaList(
        userId: Int,
        sourceId: Long,
        pageNum: Int = 1,
        popular: Boolean,
    ): PagedMangaListDataClass {
        require(pageNum > 0) {
            "pageNum = $pageNum is not in valid range"
        }
        val source = getCatalogueSourceOrStub(sourceId)
        val mangasPage =
            if (popular) {
                source.getPopularManga(pageNum)
            } else {
                if (source.supportsLatest) {
                    source.getLatestUpdates(pageNum)
                } else {
                    throw Exception("Source $source doesn't support latest")
                }
            }
        return mangasPage.processEntries(userId, sourceId)
    }

    fun MangasPage.insertOrUpdate(
        userId: Int,
        sourceId: Long,
    ): List<Int> =
        transaction {
            val existingMangaUrlsToId =
                MangaTable
                    .leftJoin(MangaUserTable)
                    .selectAll()
                    .where {
                        (MangaTable.sourceReference eq sourceId) and
                            (MangaTable.url inList mangas.map { it.url })
                    }.groupBy { it[MangaTable.url] }
            val existingMangaUrls = existingMangaUrlsToId.map { it.key }

            val mangasToInsert = mangas.filter { !existingMangaUrls.contains(it.url) }

            val insertedMangaUrlsToId =
                MangaTable
                    .batchInsert(mangasToInsert) {
                        this[MangaTable.url] = it.url
                        this[MangaTable.title] = it.title

                        this[MangaTable.artist] = it.artist
                        this[MangaTable.author] = it.author
                        this[MangaTable.description] = it.description
                        this[MangaTable.genre] = it.genre
                        this[MangaTable.status] = it.status
                        this[MangaTable.thumbnail_url] = it.thumbnail_url
                        this[MangaTable.updateStrategy] = it.update_strategy.name

                        this[MangaTable.sourceReference] = sourceId
                    }.associate { Pair(it[MangaTable.url], it[MangaTable.id].value) }

            // delete thumbnail in case cached data still exists
            insertedMangaUrlsToId.forEach { (_, id) -> Manga.clearThumbnail(id) }

            val mangaToUpdate =
                mangas
                    .mapNotNull { sManga ->
                        existingMangaUrlsToId[sManga.url]?.let { sManga to it }
                    }.filterNot { (_, resultRows) ->
                        resultRows.any { it[MangaUserTable.inLibrary] } // todo
                    }

            if (mangaToUpdate.isNotEmpty()) {
                BatchUpdateStatement(MangaTable).apply {
                    mangaToUpdate.forEach { (sManga, mangas) ->
                        val manga = mangas.first()
                        addBatch(EntityID(manga[MangaTable.id].value, MangaTable))
                        this[MangaTable.title] = sManga.title
                        this[MangaTable.artist] = sManga.artist ?: manga[MangaTable.artist]
                        this[MangaTable.author] = sManga.author ?: manga[MangaTable.author]
                        this[MangaTable.description] = sManga.description ?: manga[MangaTable.description]
                        this[MangaTable.genre] = sManga.genre ?: manga[MangaTable.genre]
                        this[MangaTable.status] = sManga.status
                        this[MangaTable.thumbnail_url] = sManga.thumbnail_url ?: manga[MangaTable.thumbnail_url]
                        this[MangaTable.updateStrategy] = sManga.update_strategy.name
                        if (!sManga.thumbnail_url.isNullOrEmpty() && manga[MangaTable.thumbnail_url] != sManga.thumbnail_url) {
                            this[MangaTable.thumbnailUrlLastFetched] = Instant.now().epochSecond
                            Manga.clearThumbnail(manga[MangaTable.id].value)
                        } else {
                            this[MangaTable.thumbnailUrlLastFetched] =
                                manga[MangaTable.thumbnailUrlLastFetched]
                        }
                    }
                    execute(this@transaction)
                }
            }

            val mangaUrlsToId =
                existingMangaUrlsToId
                    .mapValues { it.value.first()[MangaTable.id].value } + insertedMangaUrlsToId

            mangas.map { manga ->
                mangaUrlsToId[manga.url]
                    ?: throw Exception("MangaList::insertOrGet($sourceId): Something went wrong inserting browsed source mangas")
            }
        }

    fun MangasPage.processEntries(
        userId: Int,
        sourceId: Long,
    ): PagedMangaListDataClass {
        val mangasPage = this
        val mangaList =
            transaction {
                val mangaIds = insertOrUpdate(userId, sourceId)
                return@transaction MangaTable
                    .getWithUserData(userId)
                    .selectAll()
                    .where {
                        MangaTable.id inList mangaIds
                    }.map { MangaTable.toDataClass(userId, it) }
            }
        return PagedMangaListDataClass(
            mangaList,
            mangasPage.hasNextPage,
        )
    }
}
