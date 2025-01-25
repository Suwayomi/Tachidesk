package suwayomi.tachidesk.global.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.global.impl.GlobalMeta
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.formParam
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.withOperation

object GlobalMetaController {
    /** used to modify a category's meta parameters */
    val getMeta =
        handler(
            documentWith = {
                withOperation {
                    summary("Server level meta mapping")
                    description("Get a list of globally stored key-value mapping, you can set values for whatever you want inside it.")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.json(GlobalMeta.getMetaMap(userId))
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** used to modify global meta parameters */
    val modifyMeta =
        handler(
            formParam<String>("key"),
            formParam<String>("value"),
            documentWith = {
                withOperation {
                    summary("Add meta data to the global meta mapping")
                    description("A simple Key-Value stored at server global level, you can set values for whatever you want inside it.")
                }
            },
            behaviorOf = { ctx, key, value ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                GlobalMeta.modifyMeta(userId, key, value)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )
}
