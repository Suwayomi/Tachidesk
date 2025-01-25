package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.leftJoin
import suwayomi.tachidesk.global.model.table.UserTable

object MangaUserTable : IntIdTable() {
    val manga = reference("manga", MangaTable, ReferenceOption.CASCADE)
    val user = reference("user", UserTable, ReferenceOption.CASCADE)
    val inLibrary = bool("in_library").default(false)
    val inLibraryAt = long("in_library_at").default(0)
}

fun MangaTable.getWithUserData(userId: Int) =
    leftJoin(
        MangaUserTable,
        onColumn = { MangaTable.id },
        otherColumn = { MangaUserTable.manga },
        additionalConstraint = { MangaUserTable.user eq userId },
    )
