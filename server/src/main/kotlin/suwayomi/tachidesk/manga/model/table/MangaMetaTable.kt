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
import suwayomi.tachidesk.manga.model.table.MangaMetaTable.ref

/**
 * Metadata storage for clients, about Manga with id == [ref].
 *
 *  For example, if you added reader mode(with the key juiReaderMode) such as webtoon to a manga object,
 *  this is what will show up when you request that manga from the api again
 *
 * {
 *   "id": 10,
 *   "title": "Isekai manga",
 *   "meta": {
 *     "juiReaderMode": "webtoon"
 *   }
 * }
 */
object MangaMetaTable : IntIdTable() {
    val key = varchar("key", 256)
    val value = varchar("value", 4096)
    val ref = reference("manga_ref", MangaTable, ReferenceOption.CASCADE)
    val user = reference("user", UserTable, ReferenceOption.CASCADE)
}
