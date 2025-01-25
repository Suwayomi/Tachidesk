package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.Manga.getManga
import suwayomi.tachidesk.manga.impl.util.lang.isEmpty
import suwayomi.tachidesk.manga.impl.util.lang.isNotEmpty
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import java.time.Instant

object Library {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun addMangaToLibrary(
        userId: Int,
        mangaId: Int,
    ) {
        val manga = getManga(userId, mangaId)
        if (!manga.inLibrary) {
            transaction {
                val defaultCategories =
                    CategoryTable
                        .selectAll()
                        .where {
                            MangaUserTable.user eq userId and
                                (CategoryTable.isDefault eq true) and
                                (CategoryTable.id neq Category.DEFAULT_CATEGORY_ID)
                        }.toList()
                val existingCategories =
                    CategoryMangaTable
                        .selectAll()
                        .where {
                            MangaUserTable.user eq userId and (CategoryMangaTable.manga eq mangaId)
                        }.toList()

                if (MangaUserTable.selectAll().where { MangaUserTable.user eq userId and (MangaUserTable.manga eq mangaId) }.isEmpty()) {
                    MangaUserTable.insert {
                        it[MangaUserTable.manga] = mangaId
                        it[MangaUserTable.user] = userId
                        it[inLibrary] = true
                        it[inLibraryAt] = Instant.now().epochSecond
                    }
                } else {
                    MangaUserTable.update({ MangaUserTable.user eq userId and (MangaUserTable.manga eq mangaId) }) {
                        it[inLibrary] = true
                        it[inLibraryAt] = Instant.now().epochSecond
                    }
                }

                if (existingCategories.isEmpty()) {
                    defaultCategories.forEach { category ->
                        CategoryMangaTable.insert {
                            it[CategoryMangaTable.category] = category[CategoryTable.id].value
                            it[CategoryMangaTable.manga] = mangaId
                            it[CategoryMangaTable.user] = userId
                        }
                    }
                }
            }.apply {
                handleMangaThumbnail(mangaId)
            }
        }
    }

    suspend fun removeMangaFromLibrary(
        userId: Int,
        mangaId: Int,
    ) {
        val manga = getManga(userId, mangaId)
        if (manga.inLibrary) {
            transaction {
                MangaUserTable.update({ MangaUserTable.user eq userId and (MangaUserTable.manga eq mangaId) }) {
                    it[inLibrary] = false
                }
            }.apply {
                handleMangaThumbnail(mangaId)
            }
        }
    }

    fun handleMangaThumbnail(mangaId: Int) {
        scope.launch {
            val mangaInLibrary =
                transaction {
                    MangaUserTable
                        .selectAll()
                        .where {
                            MangaUserTable.manga eq mangaId and (MangaUserTable.inLibrary eq true)
                        }.isNotEmpty()
                }
            try {
                if (mangaInLibrary) {
                    ThumbnailDownloadHelper.download(mangaId)
                } else {
                    ThumbnailDownloadHelper.delete(mangaId)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
