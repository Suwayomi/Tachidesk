package suwayomi.tachidesk.global.impl

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.global.model.table.GlobalMetaTable

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

object GlobalMeta {
    fun modifyMeta(
        userId: Int,
        key: String,
        value: String,
    ) {
        transaction {
            val meta =
                transaction {
                    GlobalMetaTable.selectAll().where { GlobalMetaTable.key eq key and (GlobalMetaTable.user eq userId) }
                }.firstOrNull()

            if (meta == null) {
                GlobalMetaTable.insert {
                    it[GlobalMetaTable.key] = key
                    it[GlobalMetaTable.value] = value
                    it[GlobalMetaTable.user] = userId
                }
            } else {
                GlobalMetaTable.update({ GlobalMetaTable.key eq key and (GlobalMetaTable.user eq userId) }) {
                    it[GlobalMetaTable.value] = value
                }
            }
        }
    }

    fun getMetaMap(userId: Int): Map<String, String> =
        transaction {
            GlobalMetaTable
                .selectAll()
                .where { GlobalMetaTable.user eq userId }
                .associate { it[GlobalMetaTable.key] to it[GlobalMetaTable.value] }
        }
}
