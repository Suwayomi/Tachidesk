package suwayomi.tachidesk.manga.controller

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupImport
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupValidator
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import suwayomi.tachidesk.server.util.handler
import suwayomi.tachidesk.server.util.withOperation

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

object BackupController {
    /** expects a Tachiyomi protobuf backup in the body */
    val protobufImport =
        handler(
            documentWith = {
                withOperation {
                    summary("Restore a backup")
                    description("Expects a Tachiyomi protobuf backup in the body")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        ProtoBackupImport.restoreLegacy(userId, ctx.bodyInputStream())
                    }.thenApply {
                        ctx.json(it)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
            },
        )

    /** expects a Tachiyomi protobuf backup as a file upload, the file must be named "backup.proto.gz" */
    val protobufImportFile =
        handler(
            documentWith = {
                withOperation {
                    summary("Restore a backup file")
                    description("Expects a Tachiyomi protobuf backup as a file upload, the file must be named \"backup.proto.gz\"")
                }
                uploadedFile("backup.proto.gz") {
                    it.description("Protobuf backup")
                    it.required(true)
                }
            },
            behaviorOf = { ctx ->
                // TODO: rewrite this with ctx.uploadedFiles(), don't call the multipart field "backup.proto.gz"
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        ProtoBackupImport.restoreLegacy(userId, ctx.uploadedFile("backup.proto.gz")!!.content())
                    }.thenApply {
                        ctx.json(it)
                    }
                }
            },
            withResults = {
                httpCode(HttpStatus.OK)
                httpCode(HttpStatus.NOT_FOUND)
            },
        )

    /** returns a Tachiyomi protobuf backup created from the current database as a body */
    val protobufExport =
        handler(
            documentWith = {
                withOperation {
                    summary("Create a backup")
                    description("Returns a Tachiyomi protobuf backup created from the current database as a body")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.contentType("application/octet-stream")
                ctx.future {
                    future {
                        ProtoBackupExport.createBackup(
                            userId,
                            BackupFlags(
                                includeManga = true,
                                includeCategories = true,
                                includeChapters = true,
                                includeTracking = true,
                                includeHistory = true,
                            ),
                        )
                    }.thenApply { ctx.result(it) }
                }
            },
            withResults = {
                stream(HttpStatus.OK)
            },
        )

    /** returns a Tachiyomi protobuf backup created from the current database as a file */
    val protobufExportFile =
        handler(
            documentWith = {
                withOperation {
                    summary("Create a backup file")
                    description("Returns a Tachiyomi protobuf backup created from the current database as a file")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.contentType("application/octet-stream")

                ctx.header("Content-Disposition", """attachment; filename="${Backup.getFilename()}"""")
                ctx.future {
                    future {
                        ProtoBackupExport.createBackup(
                            userId,
                            BackupFlags(
                                includeManga = true,
                                includeCategories = true,
                                includeChapters = true,
                                includeTracking = true,
                                includeHistory = true,
                            ),
                        )
                    }.thenApply { ctx.result(it) }
                }
            },
            withResults = {
                stream(HttpStatus.OK)
            },
        )

    /** Reports missing sources and trackers, expects a Tachiyomi protobuf backup in the body */
    val protobufValidate =
        handler(
            documentWith = {
                withOperation {
                    summary("Validate a backup")
                    description("Reports missing sources and trackers, expects a Tachiyomi protobuf backup in the body")
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        ProtoBackupValidator.validate(userId, ctx.bodyInputStream())
                    }.thenApply {
                        ctx.json(it)
                    }
                }
            },
            withResults = {
                json<ProtoBackupValidator.ValidationResult>(HttpStatus.OK)
            },
        )

    /** Reports missing sources and trackers, expects a Tachiyomi protobuf backup as a file upload, the file must be named "backup.proto.gz" */
    val protobufValidateFile =
        handler(
            documentWith = {
                withOperation {
                    summary("Validate a backup file")
                    description(
                        "Reports missing sources and trackers, " +
                            "expects a Tachiyomi protobuf backup as a file upload, " +
                            "the file must be named \"backup.proto.gz\"",
                    )
                }
                uploadedFile("backup.proto.gz") {
                    it.description("Protobuf backup")
                    it.required(true)
                }
            },
            behaviorOf = { ctx ->
                val userId = ctx.getAttribute(Attribute.TachideskUser).requireUser()
                ctx.future {
                    future {
                        ProtoBackupValidator.validate(userId, ctx.uploadedFile("backup.proto.gz")!!.content())
                    }.thenApply {
                        ctx.json(it)
                    }
                }
            },
            withResults = {
                json<ProtoBackupValidator.ValidationResult>(HttpStatus.OK)
            },
        )
}
