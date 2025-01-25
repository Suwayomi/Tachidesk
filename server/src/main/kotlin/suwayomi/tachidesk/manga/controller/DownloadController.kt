package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import io.javalin.websocket.WsConfig
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.download.DownloadManager
import suwayomi.tachidesk.manga.impl.download.DownloadManager.EnqueueInput
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.injectLazy

object DownloadController {
    private val json: Json by injectLazy()

    /** Download queue stats */
    fun downloadsWS(ws: WsConfig) {
        ws.onConnect { ctx ->
            DownloadManager.addClient(ctx)
            DownloadManager.notifyClient(ctx)
        }
        ws.onMessage { ctx ->
            DownloadManager.handleRequest(ctx)
        }
        ws.onClose { ctx ->
            DownloadManager.removeClient(ctx)
        }
    }

    /** Start the downloader */
    val start =
        handler(
            documentWith = {
                withOperation {
                    summary("Downloader start")
                    description("Start the downloader")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                DownloadManager.start()
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** Stop the downloader */
    val stop =
        handler(
            documentWith = {
                withOperation {
                    summary("Downloader stop")
                    description("Stop the downloader")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { DownloadManager.stop() }
                        .thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** clear download queue */
    val clear =
        handler(
            documentWith = {
                withOperation {
                    summary("Downloader clear")
                    description("Clear download queue")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { DownloadManager.clear() }
                        .thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** Queue single chapter for download */
    val queueChapter =
        handler(
            pathParam<Int>("chapterIndex"),
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Downloader add single chapter")
                    description("Queue single chapter for download")
                }
            },
            behaviorOf = { ctx, chapterIndex, mangaId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        DownloadManager.enqueueWithChapterIndex(mangaId, chapterIndex)
                    }.thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val queueChapters =
        handler(
            documentWith = {
                withOperation {
                    summary("Downloader add multiple chapters")
                    description("Queue multiple chapters for download")
                }
                body<EnqueueInput>()
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val inputs = json.decodeFromString<EnqueueInput>(ctx.body())
                ctx.future {
                    future {
                        DownloadManager.enqueue(inputs)
                    }.thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** delete multiple chapters from download queue */
    val unqueueChapters =
        handler(
            documentWith = {
                withOperation {
                    summary("Downloader remove multiple downloads")
                    description("Remove multiple chapters downloads from queue")
                }
                body<EnqueueInput>()
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val input = json.decodeFromString<EnqueueInput>(ctx.body())
                ctx.future {
                    future {
                        DownloadManager.dequeue(input)
                    }.thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** delete chapter from download queue */
    val unqueueChapter =
        handler(
            pathParam<Int>("chapterIndex"),
            pathParam<Int>("mangaId"),
            documentWith = {
                withOperation {
                    summary("Downloader remove chapter")
                    description("Delete chapter from download queue")
                }
            },
            behaviorOf = { ctx, chapterIndex, mangaId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                DownloadManager.dequeue(chapterIndex, mangaId)

                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** clear download queue */
    val reorderChapter =
        handler(
            pathParam<Int>("chapterIndex"),
            pathParam<Int>("mangaId"),
            pathParam<Int>("to"),
            documentWith = {
                withOperation {
                    summary("Downloader reorder chapter")
                    description("Reorder chapter in download queue")
                }
            },
            behaviorOf = { ctx, chapterIndex, mangaId, to ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                DownloadManager.reorder(chapterIndex, mangaId, to)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )
}
