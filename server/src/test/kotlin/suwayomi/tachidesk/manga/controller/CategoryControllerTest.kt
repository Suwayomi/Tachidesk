package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables

class CategoryControllerTest : ApplicationTest() {
    @Test
    fun categoryReorder() {
        Category.createCategory(1, "foo")
        Category.createCategory(1, "bar")
        val cats = Category.getCategoryList(1)
        val foo = cats.asSequence().filter { it.name == "foo" }.first()
        val bar = cats.asSequence().filter { it.name == "bar" }.first()
        assertEquals(1, foo.order)
        assertEquals(2, bar.order)
        Category.reorderCategory(1, 1, 2)
        val catsReordered = Category.getCategoryList(1)
        val fooReordered = catsReordered.asSequence().filter { it.name == "foo" }.first()
        val barReordered = catsReordered.asSequence().filter { it.name == "bar" }.first()
        assertEquals(2, fooReordered.order)
        assertEquals(1, barReordered.order)
    }

    @AfterEach
    internal fun tearDown() {
        clearTables(
            CategoryTable,
        )
    }
}
