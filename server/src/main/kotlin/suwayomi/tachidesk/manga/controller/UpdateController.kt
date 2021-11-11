package suwayomi.tachidesk.manga.controller

import io.javalin.http.Context
import io.javalin.http.HttpCode
import io.javalin.plugin.openapi.dsl.DocumentedContent
import io.javalin.plugin.openapi.dsl.OpenApiDocumentation
import io.javalin.plugin.openapi.dsl.document
import io.javalin.plugin.openapi.dsl.documented
import io.javalin.plugin.openapi.dsl.oneOf
import io.javalin.websocket.WsConfig
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.kodein.di.DI
import org.kodein.di.conf.global
import org.kodein.di.instance
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.impl.update.UpdateStatusSummary
import suwayomi.tachidesk.manga.impl.update.UpdaterSocket
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaChapterDataClass
import suwayomi.tachidesk.server.JavalinSetup.future

/*
 * Copyright (C) Contributors to the Suwayomi project
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

object UpdateController {
    private val logger = KotlinLogging.logger { }

    val recentChapters = documented( document()
        .operation {
            it.summary("Get the recently updated chapters")
        }
        .pathParam<Int>("pageNum")
        .result("OK", oneOf(DocumentedContent(from = MangaChapterDataClass::class.java, true, "application/json")))
    ) { ctx ->
        val pageNum = ctx.pathParam("pageNum").toInt()

        ctx.future(
            future {
                Chapter.getRecentChapters(pageNum)
            }
        )
    }

    val categoryUpdate = documented( document()
        .operation {
            it.summary("Start fetching all mangas in a category")
            it.description(
                """
                Will start fetching all mangas in a category based on the category id.
                If the category id is not valid, a Bad Request will be returned.
                If the category id is null, all categories will be fetched.
                """.trimIndent()
            )
        }
        .formParam<Int>("category", false)
        .result<String>("OK", "text/plain")
        .result<String>("BAD_REQUEST", "text/plain"))
     { ctx ->
        val categoryId = ctx.formParam("category")?.toIntOrNull()
        val categoriesForUpdate = ArrayList<CategoryDataClass>()
        if (categoryId == null) {
            logger.info { "Adding Library to Update Queue" }
            categoriesForUpdate.addAll(Category.getCategoryList())
        } else {
            val category = Category.getCategoryById(categoryId)
            if (category != null) {
                categoriesForUpdate.add(category)
            } else {
                logger.info { "No Category found" }
                ctx.status(HttpCode.BAD_REQUEST)
                return@documented
            }
        }
        addCategoriesToUpdateQueue(categoriesForUpdate, true)
        ctx.status(HttpCode.OK)
    }

    private fun addCategoriesToUpdateQueue(categories: List<CategoryDataClass>, clear: Boolean = false) {
        val updater by DI.global.instance<IUpdater>()
        if (clear) {
            runBlocking { updater.reset() }
        }
        categories.forEach { category ->
            val mangas = CategoryManga.getCategoryMangaList(category.id)
            mangas.forEach { manga ->
                updater.addMangaToQueue(manga)
            }
        }
    }

    fun categoryUpdateWS(ws: WsConfig) {
        ws.onConnect { ctx ->
            UpdaterSocket.addClient(ctx)
        }
        ws.onMessage { ctx ->
            UpdaterSocket.handleRequest(ctx)
        }
        ws.onClose { ctx ->
            UpdaterSocket.removeClient(ctx)
        }
    }

    val updateSummary = documented(
        document()
            .operation {
                it.summary("Get the current update summary")
                it.description(
                    """
                Will return the current update summary.
                Also will retain the past update summary till the next update.
                """.trimIndent()
                )
            }
            .result<UpdateStatusSummary>("OK", "application/json")
    ) { ctx ->
        val updater by DI.global.instance<IUpdater>()
        ctx.json(updater.getStatus().value.getSummary())
    }

}
