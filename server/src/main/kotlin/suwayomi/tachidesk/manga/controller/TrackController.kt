package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.HttpStatus
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.model.dataclass.TrackerDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.queryParam
import suwayomi.tachidesk.server.util.withOperation
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.days

object TrackController {
    private val json: Json by injectLazy()
    private val logger = KotlinLogging.logger {}

    val list =
        handler(
            documentWith = {
                withOperation {
                    summary("List Supported Trackers")
                    description("List all supported Trackers")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(Track.getTrackerList(userId))
            },
            withResults = {
                json<Array<TrackerDataClass>>(HttpStatus.OK)
            },
        )

    val login =
        handler(
            documentWith = {
                withOperation {
                    summary("Tracker Login")
                    description("Login to a tracker")
                }
                body<Track.LoginInput>()
            },
            behaviorOf = { ctx ->
                val input = json.decodeFromString<Track.LoginInput>(ctx.body())
                logger.debug { "tracker login $input" }
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Track.login(userId, input) }
                        .thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val logout =
        handler(
            documentWith = {
                withOperation {
                    summary("Tracker Logout")
                    description("Logout of a Tracker")
                }
                body<Track.LogoutInput>()
            },
            behaviorOf = { ctx ->
                val input = json.decodeFromString<Track.LogoutInput>(ctx.body())
                logger.debug { "tracker logout $input" }
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Track.logout(userId, input) }
                        .thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val search =
        handler(
            documentWith = {
                withOperation {
                    summary("Tracker Search")
                    description("Search for a title on a tracker")
                }
                body<Track.SearchInput>()
            },
            behaviorOf = { ctx ->
                val input = json.decodeFromString<Track.SearchInput>(ctx.body())
                logger.debug { "tracker search $input" }
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Track.search(userId, input) }
                        .thenApply { ctx.json(it) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    val bind =
        handler(
            queryParam<Int>("mangaId"),
            queryParam<Int>("trackerId"),
            queryParam<String>("remoteId"),
            documentWith = {
                withOperation {
                    summary("Track Record Bind")
                    description("Bind a Track Record to a Manga")
                }
            },
            behaviorOf = { ctx, mangaId, trackerId, remoteId ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Track.bind(userId, mangaId, trackerId, remoteId.toLong()) }
                        .thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    val update =
        handler(
            documentWith = {
                withOperation {
                    summary("Track Update")
                    description("Update a Track Record with the Tracker")
                }
                body<Track.UpdateInput>()
            },
            behaviorOf = { ctx ->
                val input = json.decodeFromString<Track.UpdateInput>(ctx.body())
                logger.debug { "tracker update $input" }
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Track.update(userId, input) }
                        .thenApply { ctx.status(HttpStatus.OK) }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    val thumbnail =
        handler(
            pathParam<Int>("trackerId"),
            documentWith = {
                withOperation {
                    summary("Get a tracker thumbnail")
                    description("Get a tracker thumbnail from the resources.")
                }
            },
            behaviorOf = { ctx, trackerId ->
                ctx.future {
                    future { Track.getTrackerThumbnail(trackerId) }
                        .thenApply {
                            ctx.header("content-type", it.second)
                            val httpCacheSeconds = 1.days.inWholeSeconds
                            ctx.header("cache-control", "max-age=$httpCacheSeconds")
                            ctx.result(it.first)
                        }
                }
            },
            withResults = {
                image(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )
}
