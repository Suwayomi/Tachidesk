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
import org.dataloader.DataLoaderOptions
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.cache.CustomCacheMap
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.MangaNodeList
import suwayomi.tachidesk.graphql.types.MangaNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser

class MangaDataLoader : KotlinDataLoader<Int, MangaType?> {
    override val dataLoaderName = "MangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaType?> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val manga =
                        MangaTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { MangaTable.id inList ids }
                            .map { MangaType(it) }
                            .associateBy { it.id }
                    ids.map { manga[it] }
                }
            }
        }
}

class MangaForCategoryDataLoader : KotlinDataLoader<Int, MangaNodeList> {
    override val dataLoaderName = "MangaForCategoryDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, MangaNodeList> =
        DataLoaderFactory.newDataLoader<Int, MangaNodeList> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val itemsByRef =
                        if (ids.contains(0)) {
                            MangaTable
                                .getWithUserData(userId)
                                .leftJoin(CategoryMangaTable)
                                .selectAll()
                                .where { MangaUserTable.inLibrary eq true and (CategoryMangaTable.user eq userId) }
                                .andWhere { CategoryMangaTable.manga.isNull() }
                                .map { MangaType(it) }
                                .let {
                                    mapOf(0 to it)
                                }
                        } else {
                            emptyMap()
                        } +
                            CategoryMangaTable
                                .innerJoin(MangaTable)
                                .selectAll()
                                .where { CategoryMangaTable.category inList ids and (CategoryMangaTable.user eq userId) }
                                .map { Pair(it[CategoryMangaTable.category].value, MangaType(it)) }
                                .groupBy { it.first }
                                .mapValues { it.value.map { pair -> pair.second } }

                    ids.map { (itemsByRef[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}

class MangaForSourceDataLoader : KotlinDataLoader<Long, MangaNodeList> {
    override val dataLoaderName = "MangaForSourceDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, MangaNodeList> =
        DataLoaderFactory.newDataLoader<Long, MangaNodeList> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val mangaBySourceId =
                        MangaTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { MangaTable.sourceReference inList ids }
                            .map { MangaType(it) }
                            .groupBy { it.sourceId }
                    ids.map { (mangaBySourceId[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}

class MangaForIdsDataLoader : KotlinDataLoader<List<Int>, MangaNodeList> {
    override val dataLoaderName = "MangaForIdsDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<List<Int>, MangaNodeList> =
        DataLoaderFactory.newDataLoader(
            { mangaIds ->
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                future {
                    transaction {
                        addLogger(Slf4jSqlDebugLogger)
                        val ids = mangaIds.flatten().distinct()
                        val manga =
                            MangaTable
                                .getWithUserData(userId)
                                .selectAll()
                                .where { MangaTable.id inList ids }
                                .map { MangaType(it) }
                        mangaIds.map { mangaIds ->
                            manga.filter { it.id in mangaIds }.toNodeList()
                        }
                    }
                }
            },
            DataLoaderOptions.newOptions().setCacheMap(CustomCacheMap<List<Int>, MangaNodeList>()),
        )
}
