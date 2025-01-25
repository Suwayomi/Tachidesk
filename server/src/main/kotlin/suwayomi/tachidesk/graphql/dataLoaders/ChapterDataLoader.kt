/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.ChapterNodeList
import suwayomi.tachidesk.graphql.types.ChapterNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser

class ChapterDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "ChapterDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> =
        DataLoaderFactory.newDataLoader<Int, ChapterType> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chapters =
                        ChapterTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { ChapterTable.id inList ids }
                            .map { ChapterType(it) }
                            .associateBy { it.id }
                    ids.map { chapters[it] }
                }
            }
        }
}

class ChaptersForMangaDataLoader : KotlinDataLoader<Int, ChapterNodeList> {
    override val dataLoaderName = "ChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterNodeList> =
        DataLoaderFactory.newDataLoader<Int, ChapterNodeList> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val chaptersByMangaId =
                        ChapterTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { ChapterTable.manga inList ids }
                            .map { ChapterType(it) }
                            .groupBy { it.mangaId }
                    ids.map { (chaptersByMangaId[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}

class DownloadedChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "DownloadedChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader<Int, Int> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val downloadedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.isDownloaded.count())
                            .where {
                                (ChapterTable.manga inList ids) and
                                    (ChapterTable.isDownloaded eq true)
                            }.groupBy(ChapterTable.manga)
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.isDownloaded.count()] }
                    ids.map { downloadedChapterCountByMangaId[it]?.toInt() ?: 0 }
                }
            }
        }
}

class UnreadChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "UnreadChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader<Int, Int> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val unreadChapterCountByMangaId =
                        ChapterTable
                            .getWithUserData(userId)
                            .select(ChapterTable.manga, ChapterUserTable.isRead.count())
                            .where {
                                (ChapterTable.manga inList ids) and
                                    (ChapterUserTable.isRead eq false)
                            }.groupBy(ChapterTable.manga)
                            .associate { it[ChapterTable.manga].value to it[ChapterUserTable.isRead.count()] }
                    ids.map { unreadChapterCountByMangaId[it]?.toInt() ?: 0 }
                }
            }
        }
}

class BookmarkedChapterCountForMangaDataLoader : KotlinDataLoader<Int, Int> {
    override val dataLoaderName = "BookmarkedChapterCountForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Int> =
        DataLoaderFactory.newDataLoader<Int, Int> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val bookmarkedChapterCountByMangaId =
                        ChapterTable
                            .getWithUserData(userId)
                            .select(ChapterTable.manga, ChapterUserTable.isBookmarked.count())
                            .where {
                                (ChapterTable.manga inList ids) and
                                    (ChapterUserTable.isBookmarked eq true)
                            }.groupBy(ChapterTable.manga)
                            .associate { it[ChapterTable.manga].value to it[ChapterUserTable.isBookmarked.count()] }
                    ids.map { bookmarkedChapterCountByMangaId[it]?.toInt() ?: 0 }
                }
            }
        }
}

class HasDuplicateChaptersForMangaDataLoader : KotlinDataLoader<Int, Boolean> {
    override val dataLoaderName = "HasDuplicateChaptersForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, Boolean> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val duplicatedChapterCountByMangaId =
                        ChapterTable
                            .select(ChapterTable.manga, ChapterTable.chapter_number, ChapterTable.chapter_number.count())
                            .where {
                                (
                                    ChapterTable.manga inList
                                        ids
                                ) and
                                    (ChapterTable.chapter_number greaterEq 0f)
                            }.groupBy(ChapterTable.manga, ChapterTable.chapter_number)
                            .having { ChapterTable.chapter_number.count() greater 1 }
                            .associate { it[ChapterTable.manga].value to it[ChapterTable.chapter_number.count()] }

                    ids.map { duplicatedChapterCountByMangaId.contains(it) }
                }
            }
        }
}

class LastReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LastReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> =
        DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val lastReadChaptersByMangaId =
                        ChapterTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterUserTable.lastReadAt to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> lastReadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestReadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LatestReadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> =
        DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestReadChaptersByMangaId =
                        ChapterTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterUserTable.isRead eq true) }
                            .orderBy(ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestReadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestFetchedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LatestFetchedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> =
        DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestFetchedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.fetchedAt to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestFetchedChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class LatestUploadedChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "LatestUploadedChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> =
        DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val latestUploadedChaptersByMangaId =
                        ChapterTable
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) }
                            .orderBy(ChapterTable.date_upload to SortOrder.DESC, ChapterTable.sourceOrder to SortOrder.DESC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> latestUploadedChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}

class FirstUnreadChapterForMangaDataLoader : KotlinDataLoader<Int, ChapterType?> {
    override val dataLoaderName = "FirstUnreadChapterForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, ChapterType?> =
        DataLoaderFactory.newDataLoader<Int, ChapterType?> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val firstUnreadChaptersByMangaId =
                        ChapterTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { (ChapterTable.manga inList ids) and (ChapterUserTable.isRead eq false) }
                            .orderBy(ChapterTable.sourceOrder to SortOrder.ASC)
                            .groupBy { it[ChapterTable.manga].value }
                    ids.map { id -> firstUnreadChaptersByMangaId[id]?.let { chapters -> ChapterType(chapters.first()) } }
                }
            }
        }
}
