package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.toDataClass

object Category {
    /**
     * The new category will be placed at the end of the list
     */
    fun createCategory(
        userId: Int,
        name: String,
    ): Int {
        // creating a category named Default is illegal
        if (name.equals(DEFAULT_CATEGORY_NAME, ignoreCase = true)) return -1

        return transaction {
            if (CategoryTable.selectAll().where { CategoryTable.name eq name and (CategoryTable.user eq userId) }.firstOrNull() == null) {
                val newCategoryId =
                    CategoryTable
                        .insertAndGetId {
                            it[CategoryTable.name] = name
                            it[CategoryTable.order] = Int.MAX_VALUE
                            it[CategoryTable.user] = userId
                        }.value

                normalizeCategories(userId)

                newCategoryId
            } else {
                -1
            }
        }
    }

    fun updateCategory(
        userId: Int,
        categoryId: Int,
        name: String?,
        isDefault: Boolean?,
        includeInUpdate: Int?,
        includeInDownload: Int?,
    ) {
        transaction {
            CategoryTable.update({ CategoryTable.id eq categoryId and (CategoryTable.user eq userId) }) {
                if (
                    categoryId != DEFAULT_CATEGORY_ID &&
                    name != null &&
                    !name.equals(DEFAULT_CATEGORY_NAME, ignoreCase = true)
                ) {
                    it[CategoryTable.name] = name
                }
                if (categoryId != DEFAULT_CATEGORY_ID && isDefault != null) it[CategoryTable.isDefault] = isDefault
                if (includeInUpdate != null) it[CategoryTable.includeInUpdate] = includeInUpdate
                if (includeInDownload != null) it[CategoryTable.includeInDownload] = includeInDownload
            }
        }
    }

    /**
     * Move the category from order number `from` to `to`
     */
    fun reorderCategory(
        userId: Int,
        from: Int,
        to: Int,
    ) {
        if (from == 0 || to == 0) return
        transaction {
            val categories =
                CategoryTable
                    .selectAll()
                    .where {
                        CategoryTable.id neq DEFAULT_CATEGORY_ID and (CategoryTable.user eq userId)
                    }.orderBy(CategoryTable.order to SortOrder.ASC)
                    .toMutableList()
            categories.add(to - 1, categories.removeAt(from - 1))
            categories.forEachIndexed { index, cat ->
                CategoryTable.update({ CategoryTable.id eq cat[CategoryTable.id].value and (CategoryTable.user eq userId) }) {
                    it[CategoryTable.order] = index + 1
                }
            }
            normalizeCategories(userId)
        }
    }

    fun removeCategory(
        userId: Int,
        categoryId: Int,
    ) {
        if (categoryId == DEFAULT_CATEGORY_ID) return
        transaction {
            CategoryTable.deleteWhere { CategoryTable.id eq categoryId and (CategoryTable.user eq userId) }
            normalizeCategories(userId)
        }
    }

    /** make sure category order numbers starts from 1 and is consecutive */
    fun normalizeCategories(userId: Int) {
        transaction {
            CategoryTable
                .selectAll()
                .where { (CategoryTable.user eq userId) }
                .orderBy(CategoryTable.order to SortOrder.ASC)
                .sortedWith(compareBy({ it[CategoryTable.id].value != 0 }, { it[CategoryTable.order] }))
                .forEachIndexed { index, cat ->
                    CategoryTable.update({ CategoryTable.id eq cat[CategoryTable.id].value }) {
                        it[CategoryTable.order] = index
                    }
                }
        }
    }

    private fun needsDefaultCategory(userId: Int) =
        transaction {
            MangaTable
                .getWithUserData(userId)
                .leftJoin(CategoryMangaTable)
                .selectAll()
                .where { MangaUserTable.inLibrary eq true and (CategoryMangaTable.user eq userId) }
                .andWhere { CategoryMangaTable.manga.isNull() }
                .empty()
                .not()
        }

    const val DEFAULT_CATEGORY_ID = 0
    const val DEFAULT_CATEGORY_NAME = "Default"

    fun getCategoryList(userId: Int): List<CategoryDataClass> =
        transaction {
            CategoryTable
                .selectAll()
                .where { CategoryTable.user eq userId }
                .orderBy(CategoryTable.order to SortOrder.ASC)
                .let {
                    if (needsDefaultCategory(userId)) {
                        it
                    } else {
                        it.andWhere { CategoryTable.id neq DEFAULT_CATEGORY_ID }
                    }
                }.map {
                    CategoryTable.toDataClass(it)
                }
        }

    fun getCategoryById(
        userId: Int,
        categoryId: Int,
    ): CategoryDataClass? =
        transaction {
            CategoryTable.selectAll().where { CategoryTable.id eq categoryId and (CategoryTable.user eq userId) }.firstOrNull()?.let {
                CategoryTable.toDataClass(it)
            }
        }

    fun getCategorySize(
        userId: Int,
        categoryId: Int,
    ): Int =
        transaction {
            if (categoryId == DEFAULT_CATEGORY_ID) {
                MangaTable
                    .getWithUserData(userId)
                    .leftJoin(CategoryMangaTable)
                    .selectAll()
                    .where { MangaUserTable.inLibrary eq true and (CategoryMangaTable.user eq userId) }
                    .andWhere { CategoryMangaTable.manga.isNull() }
            } else {
                CategoryMangaTable
                    .leftJoin(MangaTable.getWithUserData(userId))
                    .selectAll()
                    .where { CategoryMangaTable.category eq categoryId and (CategoryMangaTable.user eq userId) }
                    .andWhere { MangaUserTable.inLibrary eq true }
            }.count().toInt()
        }

    fun getCategoryMetaMap(
        userId: Int,
        categoryId: Int,
    ): Map<String, String> =
        transaction {
            CategoryMetaTable
                .selectAll()
                .where { CategoryMetaTable.ref eq categoryId and (CategoryMetaTable.user eq userId) }
                .associate { it[CategoryMetaTable.key] to it[CategoryMetaTable.value] }
        }

    fun modifyMeta(
        userId: Int,
        categoryId: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val meta =
                transaction {
                    CategoryMetaTable.selectAll().where {
                        (CategoryMetaTable.ref eq categoryId) and
                            (CategoryMetaTable.user eq userId) and
                            (CategoryMetaTable.key eq key)
                    }
                }.firstOrNull()

            if (meta == null) {
                CategoryMetaTable.insert {
                    it[CategoryMetaTable.key] = key
                    it[CategoryMetaTable.value] = value
                    it[CategoryMetaTable.ref] = categoryId
                    it[CategoryMetaTable.user] = userId
                }
            } else {
                CategoryMetaTable.update(
                    {
                        (CategoryMetaTable.ref eq categoryId) and
                            (CategoryMetaTable.user eq userId) and
                            (CategoryMetaTable.key eq key)
                    },
                ) {
                    it[CategoryMetaTable.value] = value
                }
            }
        }
    }
}
