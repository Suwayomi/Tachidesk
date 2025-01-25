package suwayomi.tachidesk.global.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Metadata storage for clients, server/global level.
 */
object GlobalMetaTable : IntIdTable() {
    val key = varchar("key", 256)
    val value = varchar("value", 4096)
    val user = reference("user", UserTable, ReferenceOption.CASCADE)
}
