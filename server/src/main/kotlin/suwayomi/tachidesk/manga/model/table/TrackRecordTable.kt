package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import suwayomi.tachidesk.global.model.table.UserTable

object TrackRecordTable : IntIdTable() {
    val mangaId = reference("manga_id", MangaTable, ReferenceOption.CASCADE)
    val trackerId = integer("sync_id")
    val remoteId = long("remote_id")
    val libraryId = long("library_id").nullable()
    val title = varchar("title", 512)
    val lastChapterRead = double("last_chapter_read")
    val totalChapters = integer("total_chapters")
    val status = integer("status")
    val score = double("score")
    val remoteUrl = varchar("remote_url", 512)
    val startDate = long("start_date")
    val finishDate = long("finish_date")
    val user = reference("user", UserTable, ReferenceOption.CASCADE)
}
