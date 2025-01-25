package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.MangaList
import suwayomi.tachidesk.manga.impl.Search
import suwayomi.tachidesk.manga.impl.Search.FilterChange
import suwayomi.tachidesk.manga.impl.Search.FilterData
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.Source.SourcePreferenceChange
import suwayomi.tachidesk.manga.model.dataclass.PagedMangaListDataClass
import suwayomi.tachidesk.manga.model.dataclass.SourceDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.injectLazy

object SourceController {
    /** list of sources */
    val list =
        handler(
            documentWith = {
                withOperation {
                    summary("Sources list")
                    description("List of sources")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Source.getSourceList())
            },
            withResults = {
                json<Array<SourceDataClass>>(HttpStatus.OK)
            },
        )

    /** fetch source with id `sourceId` */
    val retrieve =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source fetch")
                    description("Fetch source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Source.getSource(sourceId)!!)
            },
            withResults = {
                json<SourceDataClass>(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    /** popular mangas from source with id `sourceId` */
    val popular =
        handler(
            pathParam<Long>("sourceId"),
            pathParam<Int>("pageNum"),
            documentWith = {
                withOperation {
                    summary("Source popular manga")
                    description("Popular mangas from source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId, pageNum ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        MangaList.getMangaList(userId, sourceId, pageNum, popular = true)
                    }.thenApply { ctx.json(it) }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** latest mangas from source with id `sourceId` */
    val latest =
        handler(
            pathParam<Long>("sourceId"),
            pathParam<Int>("pageNum"),
            documentWith = {
                withOperation {
                    summary("Source latest manga")
                    description("Latest mangas from source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId, pageNum ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        MangaList.getMangaList(userId, sourceId, pageNum, popular = false)
                    }.thenApply { ctx.json(it) }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** fetch preferences of source with id `sourceId` */
    val getPreferences =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source preferences")
                    description("Fetch preferences of source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Source.getSourcePreferences(sourceId))
            },
            withResults = {
                json<Array<Source.PreferenceObject>>(HttpStatus.OK)
            },
        )

    /** set one preference of source with id `sourceId` */
    val setPreference =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source preference set")
                    description("Set one preference of source with id `sourceId`")
                }
                body<SourcePreferenceChange>()
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val preferenceChange = ctx.bodyAsClass(SourcePreferenceChange::class.java)
                ctx.json(Source.setSourcePreference(sourceId, preferenceChange.position, preferenceChange.value))
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** fetch filters of source with id `sourceId` */
    val getFilters =
        handler(
            pathParam<Long>("sourceId"),
            queryParam("reset", false),
            documentWith = {
                withOperation {
                    summary("Source filters")
                    description("Fetch filters of source with id `sourceId`")
                }
            },
            behaviorOf = { ctx, sourceId, reset ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Search.getFilterList(sourceId, reset))
            },
            withResults = {
                json<Array<Search.FilterObject>>(HttpStatus.OK)
            },
        )

    private val json: Json by injectLazy()

    /** change filters of source with id `sourceId` */
    val setFilters =
        handler(
            pathParam<Long>("sourceId"),
            documentWith = {
                withOperation {
                    summary("Source filters set")
                    description("Change filters of source with id `sourceId`")
                }
                body<FilterChange>()
                body<Array<FilterChange>>()
            },
            behaviorOf = { ctx, sourceId ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val filterChange =
                    try {
                        json.decodeFromString<List<FilterChange>>(ctx.body())
                    } catch (e: Exception) {
                        listOf(json.decodeFromString<FilterChange>(ctx.body()))
                    }

                ctx.json(Search.setFilter(sourceId, filterChange))
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** single source search */
    val searchSingle =
        handler(
            pathParam<Long>("sourceId"),
            queryParam("searchTerm", ""),
            queryParam("pageNum", 1),
            documentWith = {
                withOperation {
                    summary("Source search")
                    description("Single source search")
                }
            },
            behaviorOf = { ctx, sourceId, searchTerm, pageNum ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Search.sourceSearch(userId, sourceId, searchTerm, pageNum) }
                        .thenApply { ctx.json(it) }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** quick search single source filter */
    val quickSearchSingle =
        handler(
            pathParam<Long>("sourceId"),
            queryParam("pageNum", 1),
            documentWith = {
                withOperation {
                    summary("Source manga quick search")
                    description("Returns list of manga from source matching posted searchTerm and filter")
                }
                body<FilterData>()
            },
            behaviorOf = { ctx, sourceId, pageNum ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val filter = json.decodeFromString<FilterData>(ctx.body())
                ctx.future {
                    future { Search.sourceFilter(userId, sourceId, pageNum, filter) }
                        .thenApply { ctx.json(it) }
                }
            },
            withResults = {
                json<PagedMangaListDataClass>(HttpStatus.OK)
            },
        )

    /** all source search */
    val searchAll =
        handler(
            pathParam<String>("searchTerm"),
            documentWith = {
                withOperation {
                    summary("Source global search")
                    description("All source search")
                }
            },
            behaviorOf = { ctx, searchTerm ->
                // TODO
                ctx.json(Search.sourceGlobalSearch(searchTerm))
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )
}
