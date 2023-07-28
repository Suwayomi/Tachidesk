package suwayomi.tachidesk.server.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import dorkbox.desktop.Desktop
import suwayomi.tachidesk.server.serverConfig

object Browser {
    private val appIP = if (serverConfig.ip.value == "0.0.0.0") "127.0.0.1" else serverConfig.ip.value
    private val appBaseUrl = "http://$appIP:${serverConfig.port.value}"

    private val electronInstances = mutableListOf<Any>()

    fun openInBrowser() {
        if (serverConfig.webUIEnabled.value) {
            if (serverConfig.webUIInterface.value == ("electron")) {
                try {
                    val electronPath = serverConfig.electronPath.value
                    electronInstances.add(ProcessBuilder(electronPath, appBaseUrl).start())
                } catch (e: Throwable) { // cover both java.lang.Exception and java.lang.Error
                    e.printStackTrace()
                }
            } else {
                try {
                    Desktop.browseURL(appBaseUrl)
                } catch (e: Throwable) { // cover both java.lang.Exception and java.lang.Error
                    e.printStackTrace()
                }
            }
        }
    }
}
