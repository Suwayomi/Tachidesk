package suwayomi.tachidesk.manga.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.HttpStatus
import io.javalin.websocket.WsConfig
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.impl.update.UpdateStatus
import suwayomi.tachidesk.manga.impl.update.UpdaterSocket
import suwayomi.tachidesk.manga.model.dataclass.MangaChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.PaginatedList
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.formParam
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

object UpdateController {
    private val logger = KotlinLogging.logger { }

    /** get recently updated manga chapters */
    val recentChapters =
        handler(
            pathParam<Int>("pageNum"),
            documentWith = {
                withOperation {
                    summary("Updates fetch")
                    description("Get recently updated manga chapters")
                }
            },
            behaviorOf = { ctx, pageNum ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        Chapter.getRecentChapters(userId, pageNum)
                    }.thenApply { ctx.json(it) }
                }
            },
            withResults = {
                json<PagedMangaChapterListDataClass>(HttpStatus.OK)
            },
        )

    /**
     * Class made for handling return type in the documentation for [recentChapters],
     * since OpenApi cannot handle runtime generics.
     */
    private class PagedMangaChapterListDataClass : PaginatedList<MangaChapterDataClass>(emptyList(), false)

    val categoryUpdate =
        handler(
            formParam<Int?>("categoryId"),
            documentWith = {
                withOperation {
                    summary("Updater start")
                    description("Starts the updater")
                }
            },
            behaviorOf = { ctx, categoryId ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val updater = Injekt.get<IUpdater>()
                if (categoryId == null) {
                    logger.info { "Adding Library to Update Queue" }
                    updater.addCategoriesToUpdateQueue(
                        Category.getCategoryList(userId),
                        clear = true,
                        forceAll = false,
                    )
                } else {
                    val category = Category.getCategoryById(userId, categoryId)
                    if (category != null) {
                        updater.addCategoriesToUpdateQueue(
                            listOf(category),
                            clear = true,
                            forceAll = true,
                        )
                    } else {
                        logger.info { "No Category found" }
                        ctx.status(HttpStatus.BAD_REQUEST)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.BAD_REQUEST)
            },
        )

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

    val updateSummary =
        handler(
            documentWith = {
                withOperation {
                    summary("Updater summary")
                    description("Gets the latest updater summary")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val updater = Injekt.get<IUpdater>()
                ctx.json(updater.statusDeprecated.value)
            },
            withResults = {
                json<UpdateStatus>(HttpStatus.OK)
            },
        )

    val reset =
        handler(
            documentWith = {
                withOperation {
                    summary("Updater reset")
                    description("Stops and resets the Updater")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val updater = Injekt.get<IUpdater>()
                logger.info { "Resetting Updater" }
                ctx.future {
                    future {
                        updater.reset()
                    }.thenApply {
                        ctx.status(HttpStatus.OK)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )
}
