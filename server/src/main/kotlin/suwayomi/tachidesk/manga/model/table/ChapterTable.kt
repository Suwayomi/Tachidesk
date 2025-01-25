package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.Chapter.getChapterMetaMap
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.table.MangaTable.nullable

object ChapterTable : IntIdTable() {
    val url = varchar("url", 2048)
    val name = varchar("name", 512)
    val date_upload = long("date_upload").default(0)
    val chapter_number = float("chapter_number").default(-1f)
    val scanlator = varchar("scanlator", 128).nullable()

    val fetchedAt = long("fetched_at").default(0)

    val sourceOrder = integer("source_order")

    /** the real url of a chapter used for the "open in WebView" feature */
    val realUrl = varchar("real_url", 2048).nullable()

    val isDownloaded = bool("is_downloaded").default(false)

    val pageCount = integer("page_count").default(-1)

    val manga = reference("manga", MangaTable, ReferenceOption.CASCADE)
}

fun ChapterTable.toDataClass(
    userId: Int,
    chapterEntry: ResultRow,
) = ChapterDataClass(
    id = chapterEntry[id].value,
    url = chapterEntry[url],
    name = chapterEntry[name],
    uploadDate = chapterEntry[date_upload],
    chapterNumber = chapterEntry[chapter_number],
    scanlator = chapterEntry[scanlator],
    mangaId = chapterEntry[manga].value,
    read = chapterEntry.getOrNull(ChapterUserTable.isRead) ?: false,
    bookmarked = chapterEntry.getOrNull(ChapterUserTable.isBookmarked) ?: false,
    lastPageRead = chapterEntry.getOrNull(ChapterUserTable.lastPageRead) ?: 0,
    lastReadAt = chapterEntry.getOrNull(ChapterUserTable.lastReadAt) ?: 0,
    index = chapterEntry[sourceOrder],
    fetchedAt = chapterEntry[fetchedAt],
    realUrl = chapterEntry[realUrl],
    downloaded = chapterEntry[isDownloaded],
    pageCount = chapterEntry[pageCount],
    chapterCount =
        transaction {
            ChapterTable
                .selectAll()
                .where { manga eq chapterEntry[manga].value }
                .count()
                .toInt()
        },
    meta = getChapterMetaMap(userId, chapterEntry[id]),
)
