package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.formParam
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.withOperation

object CategoryController {
    /** category list */
    val categoryList =
        handler(
            documentWith = {
                withOperation {
                    summary("Category list")
                    description("get a list of categories")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Category.getCategoryList(userId))
            },
            withResults = {
                json<Array<CategoryDataClass>>(HttpStatus.OK)
            },
        )

    /** category create */
    val categoryCreate =
        handler(
            formParam<String>("name"),
            documentWith = {
                withOperation {
                    summary("Category create")
                    description("Create a category")
                }
            },
            behaviorOf = { ctx, name ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                if (Category.createCategory(userId, name) != -1) {
                    ctx.status(200)
                } else {
                    ctx.status(HttpStatus.BAD_REQUEST)
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.BAD_REQUEST)
            },
        )

    /** category modification */
    val categoryModify =
        handler(
            pathParam<Int>("categoryId"),
            formParam<String?>("name"),
            formParam<Boolean?>("default"),
            formParam<Int?>("includeInUpdate"),
            formParam<Int?>("includeInDownload"),
            documentWith = {
                withOperation {
                    summary("Category modify")
                    description("Modify a category")
                }
            },
            behaviorOf = { ctx, categoryId, name, isDefault, includeInUpdate, includeInDownload ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                Category.updateCategory(userId, categoryId, name, isDefault, includeInUpdate, includeInDownload)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** category delete */
    val categoryDelete =
        handler(
            pathParam<Int>("categoryId"),
            documentWith = {
                withOperation {
                    summary("Category delete")
                    description("Delete a category")
                }
            },
            behaviorOf = { ctx, categoryId ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                Category.removeCategory(userId, categoryId)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** returns the manga list associated with a category */
    val categoryMangas =
        handler(
            pathParam<Int>("categoryId"),
            documentWith = {
                withOperation {
                    summary("Category manga")
                    description("Returns the manga list associated with a category")
                }
            },
            behaviorOf = { ctx, categoryId ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(CategoryManga.getCategoryMangaList(userId, categoryId))
            },
            withResults = {
                json<Array<MangaDataClass>>(HttpStatus.OK)
            },
        )

    /** category re-ordering */
    val categoryReorder =
        handler(
            formParam<Int>("from"),
            formParam<Int>("to"),
            documentWith = {
                withOperation {
                    summary("Category re-ordering")
                    description("Re-order a category")
                }
            },
            behaviorOf = { ctx, from, to ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                Category.reorderCategory(userId, from, to)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** used to modify a category's meta parameters */
    val meta =
        handler(
            pathParam<Int>("categoryId"),
            formParam<String>("key"),
            formParam<String>("value"),
            documentWith = {
                withOperation {
                    summary("Add meta data to category")
                    description("A simple Key-Value storage in the manga object, you can set values for whatever you want inside it.")
                }
            },
            behaviorOf = { ctx, categoryId, key, value ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                Category.modifyMeta(userId, categoryId, key, value)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )
}
