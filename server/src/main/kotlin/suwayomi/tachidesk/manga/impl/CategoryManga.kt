package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.wrapAsExpression
import suwayomi.tachidesk.manga.impl.Category.DEFAULT_CATEGORY_ID
import suwayomi.tachidesk.manga.impl.util.lang.isEmpty
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.toDataClass

object CategoryManga {
    fun addMangaToCategory(
        userId: Int,
        mangaId: Int,
        categoryId: Int,
    ) {
        if (categoryId == DEFAULT_CATEGORY_ID) return

        fun notAlreadyInCategory() =
            CategoryMangaTable
                .selectAll()
                .where {
                    (CategoryMangaTable.category eq categoryId) and
                        (CategoryMangaTable.manga eq mangaId) and
                        (CategoryMangaTable.user eq userId)
                }.isEmpty()

        transaction {
            if (notAlreadyInCategory()) {
                CategoryMangaTable.insert {
                    it[CategoryMangaTable.category] = categoryId
                    it[CategoryMangaTable.manga] = mangaId
                    it[CategoryMangaTable.user] = userId
                }
            }
        }
    }

    fun removeMangaFromCategory(
        userId: Int,
        mangaId: Int,
        categoryId: Int,
    ) {
        if (categoryId == DEFAULT_CATEGORY_ID) return
        transaction {
            CategoryMangaTable.deleteWhere {
                (CategoryMangaTable.category eq categoryId) and
                    (CategoryMangaTable.manga eq mangaId) and
                    (CategoryMangaTable.user eq userId)
            }
        }
    }

    /**
     * list of mangas that belong to a category
     */
    fun getCategoryMangaList(
        userId: Int,
        categoryId: Int,
    ): List<MangaDataClass> {
        // Select the required columns from the MangaTable and add the aggregate functions to compute unread, download, and chapter counts
        val unreadCount =
            wrapAsExpression<Long>(
                ChapterTable
                    .getWithUserData(userId)
                    .select(
                        ChapterTable.id.count(),
                    ).where {
                        (
                            (ChapterUserTable.isRead eq false or (ChapterUserTable.isRead.isNull())) and
                                (ChapterTable.manga eq MangaTable.id)
                        )
                    },
            )
        val downloadedCount =
            wrapAsExpression<Long>(
                ChapterTable
                    .select(
                        ChapterTable.id.count(),
                    ).where { ((ChapterTable.isDownloaded eq true) and (ChapterTable.manga eq MangaTable.id)) },
            )

        val chapterCount = ChapterTable.id.count().alias("chapter_count")
        val lastReadAt = ChapterUserTable.lastReadAt.max().alias("last_read_at")
        val selectedColumns = MangaTable.getWithUserData(userId).columns + unreadCount + downloadedCount + chapterCount + lastReadAt

        val transform: (ResultRow) -> MangaDataClass = {
            // Map the data from the result row to the MangaDataClass
            val dataClass = MangaTable.toDataClass(userId, it)
            dataClass.lastReadAt = it[lastReadAt]
            dataClass.unreadCount = it[unreadCount]
            dataClass.downloadCount = it[downloadedCount]
            dataClass.chapterCount = it[chapterCount]
            dataClass
        }

        return transaction {
            // Fetch data from the MangaTable and join with the CategoryMangaTable, if a category is specified
            val query =
                if (categoryId == DEFAULT_CATEGORY_ID) {
                    MangaTable
                        .getWithUserData(userId)
                        .leftJoin(ChapterTable.getWithUserData(userId), { MangaTable.id }, { ChapterTable.manga })
                        .leftJoin(CategoryMangaTable)
                        .select(columns = selectedColumns)
                        .where {
                            (MangaUserTable.inLibrary eq true) and
                                (CategoryMangaTable.user eq userId) and
                                CategoryMangaTable.category.isNull()
                        }
                } else {
                    MangaTable
                        .getWithUserData(userId)
                        .innerJoin(CategoryMangaTable)
                        .leftJoin(ChapterTable.getWithUserData(userId), { MangaTable.id }, { ChapterTable.manga })
                        .select(columns = selectedColumns)
                        .where { (MangaUserTable.inLibrary eq true) and (CategoryMangaTable.category eq categoryId) }
                }

            // Join with the ChapterTable to fetch the last read chapter for each manga
            query.groupBy(*MangaTable.columns.toTypedArray()).map(transform)
        }
    }

    /**
     * list of categories that a manga belongs to
     */
    fun getMangaCategories(
        userId: Int,
        mangaId: Int,
    ): List<CategoryDataClass> =
        transaction {
            CategoryMangaTable
                .innerJoin(CategoryTable)
                .selectAll()
                .where {
                    CategoryMangaTable.manga eq mangaId and (CategoryTable.user eq userId) and (CategoryMangaTable.user eq userId)
                }.orderBy(CategoryTable.order to SortOrder.ASC)
                .map {
                    CategoryTable.toDataClass(it)
                }
        }

    fun getMangasCategories(
        userId: Int,
        mangaIDs: List<Int>,
    ): Map<Int, List<CategoryDataClass>> =
        buildMap {
            transaction {
                CategoryMangaTable
                    .innerJoin(CategoryTable)
                    .selectAll()
                    .where {
                        (CategoryTable.user eq userId) and
                            (CategoryMangaTable.user eq userId) and
                            (CategoryMangaTable.manga inList mangaIDs)
                    }.groupBy { it[CategoryMangaTable.manga] }
                    .forEach {
                        val mangaId = it.key.value
                        val categories = it.value

                        set(mangaId, categories.map { category -> CategoryTable.toDataClass(category) })
                    }
            }
        }
}
