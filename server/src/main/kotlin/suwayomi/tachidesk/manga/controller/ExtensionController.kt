package suwayomi.tachidesk.manga.controller

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.impl.extension.Extension
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.model.dataclass.ExtensionDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.pathParam
import suwayomi.tachidesk.server.util.withOperation
import kotlin.time.Duration.Companion.days

object ExtensionController {
    private val logger = KotlinLogging.logger {}

    /** list all extensions */
    val list =
        handler(
            documentWith = {
                withOperation {
                    summary("Extension list")
                    description("List all extensions")
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        ExtensionsList.getExtensionList()
                    }.thenApply {
                        ctx.json(it)
                    }
                }
            },
            withResults = {
                json<Array<ExtensionDataClass>>(HttpStatus.OK)
            },
        )

    /** install extension identified with "pkgName" */
    val install =
        handler(
            pathParam<String>("pkgName"),
            documentWith = {
                withOperation {
                    summary("Extension install")
                    description("install extension identified with \"pkgName\"")
                }
            },
            behaviorOf = { ctx, pkgName ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        Extension.installExtension(pkgName)
                    }.thenApply {
                        ctx.status(it)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.CREATED)
                httpCode(HttpStatus.FOUND)
                httpCode(HttpStatus.INTERNAL_SERVER_ERROR)
            },
        )

    /** install the uploaded apk file */
    val installFile =
        handler(
            documentWith = {
                withOperation {
                    summary("Extension install apk")
                    description("Install the uploaded apk file")
                }
                uploadedFile("file") {
                    it.description("Extension apk")
                    it.required(true)
                }
            },
            behaviorOf = { ctx ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                val uploadedFile = ctx.uploadedFile("file")!!
                logger.debug { "Uploaded extension file name: " + uploadedFile.filename() }

                ctx.future {
                    future {
                        Extension.installExternalExtension(
                            uploadedFile.content(),
                            uploadedFile.filename(),
                        )
                    }.thenApply {
                        ctx.status(it)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.CREATED)
                httpCode(HttpStatus.FOUND)
                httpCode(HttpStatus.INTERNAL_SERVER_ERROR)
            },
        )

    /** update extension identified with "pkgName" */
    val update =
        handler(
            pathParam<String>("pkgName"),
            documentWith = {
                withOperation {
                    summary("Extension update")
                    description("Update extension identified with \"pkgName\"")
                }
            },
            behaviorOf = { ctx, pkgName ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        Extension.updateExtension(pkgName)
                    }.thenApply {
                        ctx.status(it)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.CREATED)
                httpCode(HttpStatus.FOUND)
                httpCode(HttpStatus.NOT_FOUND)
                httpCode(HttpStatus.INTERNAL_SERVER_ERROR)
            },
        )

    /** uninstall extension identified with "pkgName" */
    val uninstall =
        handler(
            pathParam<String>("pkgName"),
            documentWith = {
                withOperation {
                    summary("Extension uninstall")
                    description("Uninstall extension identified with \"pkgName\"")
                }
            },
            behaviorOf = { ctx, pkgName ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                Extension.uninstallExtension(pkgName)
                ctx.status(200)
            },
            withResults = {
                httpCode(HttpStatus.CREATED)
                httpCode(HttpStatus.FOUND)
                httpCode(HttpStatus.NOT_FOUND)
                httpCode(HttpStatus.INTERNAL_SERVER_ERROR)
            },
        )

    /** icon for extension named `apkName` */
    val icon =
        handler(
            pathParam<String>("apkName"),
            documentWith = {
                withOperation {
                    summary("Extension icon")
                    description("Icon for extension named `apkName`")
                }
            },
            behaviorOf = { ctx, apkName ->
                ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future { Extension.getExtensionIcon(apkName) }
                        .thenApply {
                            ctx.header("content-type", it.second)
                            val httpCacheSeconds = 365.days.inWholeSeconds
                            ctx.header("cache-control", "max-age=$httpCacheSeconds, immutable")
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
