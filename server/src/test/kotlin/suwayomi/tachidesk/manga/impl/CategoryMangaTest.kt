package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.Category.DEFAULT_CATEGORY_ID
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CategoryMangaTest : ApplicationTest() {
    @Test
    fun getCategoryMangaList() {
        val emptyCats = CategoryManga.getCategoryMangaList(1, DEFAULT_CATEGORY_ID).size
        assertEquals(0, emptyCats, "Default category should be empty at start")
        val mangaId = createLibraryManga("Psyren")
        createChapters(mangaId, 10, true)
        assertEquals(1, CategoryManga.getCategoryMangaList(1, DEFAULT_CATEGORY_ID).size, "Default category should have one member")
        assertEquals(
            0,
            CategoryManga.getCategoryMangaList(1, DEFAULT_CATEGORY_ID)[0].unreadCount,
            "Manga should not have any unread chapters",
        )
        createChapters(mangaId, 10, false)
        assertEquals(
            10,
            CategoryManga.getCategoryMangaList(1, DEFAULT_CATEGORY_ID)[0].unreadCount,
            "Manga should have unread chapters",
        )

        val categoryId = Category.createCategory(1, "Old")
        assertEquals(
            0,
            CategoryManga.getCategoryMangaList(1, categoryId).size,
            "Newly created category shouldn't have any Mangas",
        )
        CategoryManga.addMangaToCategory(1, mangaId, categoryId)
        assertEquals(
            1,
            CategoryManga.getCategoryMangaList(1, categoryId).size,
            "Manga should been moved",
        )
        assertEquals(
            10,
            CategoryManga.getCategoryMangaList(1, categoryId)[0].unreadCount,
            "Manga should keep it's unread count in moved category",
        )
        assertEquals(
            0,
            CategoryManga.getCategoryMangaList(1, DEFAULT_CATEGORY_ID).size,
            "Manga shouldn't be member of default category after moving",
        )
    }

    @AfterEach
    internal fun tearDown() {
        clearTables(
            ChapterTable,
            CategoryMangaTable,
            MangaTable,
            CategoryTable,
        )
    }
}
