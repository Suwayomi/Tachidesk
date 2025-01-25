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
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.CategoryNodeList
import suwayomi.tachidesk.graphql.types.CategoryNodeList.Companion.toNodeList
import suwayomi.tachidesk.graphql.types.CategoryType
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.server.JavalinSetup
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser

class CategoryDataLoader : KotlinDataLoader<Int, CategoryType> {
    override val dataLoaderName = "CategoryDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, CategoryType> =
        DataLoaderFactory.newDataLoader { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val categories =
                        CategoryTable
                            .selectAll()
                            .where { CategoryTable.id inList ids and (CategoryTable.user eq userId) }
                            .map { CategoryType(it) }
                            .associateBy { it.id }
                    ids.map { categories[it] }
                }
            }
        }
}

class CategoryForIdsDataLoader : KotlinDataLoader<List<Int>, CategoryNodeList> {
    override val dataLoaderName = "CategoryForIdsDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<List<Int>, CategoryNodeList> =
        DataLoaderFactory.newDataLoader { categoryIds ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val ids = categoryIds.flatten().distinct()
                    val categories =
                        CategoryTable
                            .selectAll()
                            .where {
                                CategoryTable.id inList ids and (CategoryTable.user eq userId)
                            }.map {
                                CategoryType(
                                    it,
                                )
                            }
                    categoryIds.map { categoryIds ->
                        categories.filter { it.id in categoryIds }.toNodeList()
                    }
                }
            }
        }
}

class CategoriesForMangaDataLoader : KotlinDataLoader<Int, CategoryNodeList> {
    override val dataLoaderName = "CategoriesForMangaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, CategoryNodeList> =
        DataLoaderFactory.newDataLoader<Int, CategoryNodeList> { ids ->
            future {
                val userId = graphQLContext.getAttribute(JavalinSetup.Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val itemsByRef =
                        CategoryMangaTable
                            .innerJoin(CategoryTable)
                            .selectAll()
                            .where {
                                CategoryMangaTable.manga inList ids and
                                    (CategoryMangaTable.user eq userId) and
                                    (CategoryTable.user eq userId)
                            }.map { Pair(it[CategoryMangaTable.manga].value, CategoryType(it)) }
                            .groupBy { it.first }
                            .mapValues { it.value.map { pair -> pair.second } }
                    ids.map { (itemsByRef[it] ?: emptyList()).toNodeList() }
                }
            }
        }
}
