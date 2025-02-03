package suwayomi.tachidesk.opds.controller

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.opds.impl.Opds
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation

object OpdsController {
    private const val OPDS_MIME = "application/xml;profile=opds-catalog;charset=UTF-8"
    private const val BASE_URL = "/api/opds/v1.2"

    val rootFeed =
        handler(
            documentWith = {
                withOperation {
                    summary("OPDS Root Feed")
                    description("OPDS feed for the list of available manga sources")
                }
            },
            behaviorOf = { ctx ->
                ctx.future {
                    future {
                        Opds.getRootFeed(BASE_URL)
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
                    description("OPDS feed for a specific manga source")
                }
            },
            behaviorOf = { ctx, sourceId, pageNumber ->
                ctx.future {
                    future {
                        Opds.getSourceFeed(sourceId, BASE_URL, pageNumber ?: 1)
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
                    description("OPDS feed for chapters of a specific manga")
                }
            },
            behaviorOf = { ctx, mangaId, pageNumber ->
                ctx.future {
                    future {
                        Opds.getMangaFeed(mangaId, BASE_URL, pageNumber ?: 1)
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
