package suwayomi.tachidesk.opds

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import suwayomi.tachidesk.opds.controller.OpdsController

object OpdsAPI {
    fun defineEndpoints() {
        path("opds/v1.2") {
            get(OpdsController.rootFeed)
            get("source/{sourceId}", OpdsController.sourceFeed)
            get("manga/{mangaId}", OpdsController.mangaFeed)
        }
    }
}
