package suwayomi.tachidesk.manga.controller

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.impl.Opds
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation

object OpdsController {
    private const val OPDS_MIME = "application/xml;profile=opds-catalog;charset=UTF-8"

    val rootFeed =
        handler(
            documentWith = {
                withOperation {
                    summary("OPDS Root Feed")
                    description("Feed OPDS de lista de fuentes disponibles de mangas")
                }
            },
            behaviorOf = { ctx ->
                ctx.future {
                    future {
                        val baseUrl = "/api/v1/opds/v1.2"
                        Opds.getRootFeed(baseUrl)
                    }.thenApply { xml ->
                        ctx.contentType(OPDS_MIME).result(xml)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    val sourceFeed =
        handler(
            pathParam<Long>("sourceId"),
            queryParam<Int?>("pageNumber"),
            documentWith = {
                withOperation {
                    summary("OPDS Source Feed")
                    description("Feed OPDS de una fuente específica de mangas")
                }
            },
            behaviorOf = { ctx, sourceId, pageNumber ->
                ctx.future {
                    future {
                        val baseUrl = "/api/v1/opds/v1.2"
                        Opds.getSourceFeed(sourceId, baseUrl, pageNumber ?: 1)
                    }.thenApply { xml ->
                        ctx.contentType(OPDS_MIME).result(xml)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val mangaFeed =
        handler(
            pathParam<Int>("mangaId"),
            queryParam<Int?>("pageNumber"),
            documentWith = {
                withOperation {
                    summary("OPDS Manga Feed")
                    description("Feed OPDS de capítulos de un manga específico")
                }
            },
            behaviorOf = { ctx, mangaId, pageNumber ->
                ctx.future {
                    future {
                        val baseUrl = "/api/v1/opds/v1.2"
                        Opds.getMangaFeed(mangaId, baseUrl, pageNumber ?: 1)
                    }.thenApply { xml ->
                        ctx.contentType(OPDS_MIME).result(xml)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )
}
