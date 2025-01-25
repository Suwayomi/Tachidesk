package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.fasterxml.jackson.annotation.JsonIgnore
import okio.buffer
import okio.gzip
import okio.source
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.table.SourceTable
import java.io.InputStream

object ProtoBackupValidator {
    data class ValidationResult(
        val missingSources: List<String>,
        val missingTrackers: List<String>,
        val mangasMissingSources: List<String>,
        @JsonIgnore
        val missingSourceIds: List<Pair<Long, String>>,
    )

    fun validate(
        userId: Int,
        backup: Backup,
    ): ValidationResult {
        if (backup.backupManga.isEmpty()) {
            throw Exception("Backup does not contain any manga.")
        }

        val sources = backup.getSourceMap()

        val missingSources =
            transaction {
                sources.filter { SourceTable.selectAll().where { SourceTable.id eq it.key }.firstOrNull() == null }
            }

        val trackers =
            backup.backupManga
                .flatMap { it.tracking }
                .map { it.syncId }
                .distinct()

        val missingTrackers =
            trackers
                .mapNotNull { TrackerManager.getTracker(it) }
                .filter { !it.isLoggedIn(userId) }
                .map { it.name }
                .sorted()

        return ValidationResult(
            missingSources
                .map { "${it.value} (${it.key})" }
                .sorted(),
            missingTrackers,
            emptyList(),
            missingSources.toList(),
        )
    }

    fun validate(
        userId: Int,
        sourceStream: InputStream,
    ): ValidationResult {
        val backupString =
            sourceStream
                .source()
                .gzip()
                .buffer()
                .use { it.readByteArray() }
        val backup = ProtoBackupImport.parser.decodeFromByteArray(Backup.serializer(), backupString)

        return validate(userId, backup)
    }
}
