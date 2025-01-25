package suwayomi.tachidesk.manga.impl.download

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import suwayomi.tachidesk.manga.impl.ChapterDownloadHelper
import suwayomi.tachidesk.manga.impl.chapter.getChapterDownloadReadyByIndex
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Downloading
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Error
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Finished
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Queued
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdate
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType.ERROR
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType.FINISHED
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType.PAUSED
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType.PROGRESS
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType.STOPPED
import suwayomi.tachidesk.manga.model.table.ChapterTable
import java.util.concurrent.CopyOnWriteArrayList

class Downloader(
    private val scope: CoroutineScope,
    val sourceId: String,
    private val downloadQueue: CopyOnWriteArrayList<DownloadChapter>,
    private val notifier: (immediate: Boolean, download: DownloadUpdate?) -> Unit,
    private val onComplete: () -> Unit,
    private val onDownloadFinished: () -> Unit,
) {
    private val logger = KotlinLogging.logger("${Downloader::class.java.name} source($sourceId)")

    private var job: Job? = null
    private val availableSourceDownloads
        get() = downloadQueue.filter { it.manga.sourceId == sourceId }

    class StopDownloadException : Exception("Cancelled download")

    class PauseDownloadException : Exception("Pause download")

    private suspend fun step(
        downloadUpdate: DownloadUpdate?,
        immediate: Boolean,
    ) {
        val download = downloadUpdate?.downloadChapter
        notifier(immediate, downloadUpdate)
        currentCoroutineContext().ensureActive()
        if (download != null && download != availableSourceDownloads.firstOrNull { it.state != Error }) {
            if (download in downloadQueue) {
                throw PauseDownloadException()
            } else {
                throw StopDownloadException()
            }
        }
    }

    val isActive
        get() = job?.isActive == true

    fun start() {
        if (!isActive) {
            job =
                scope
                    .launch {
                        run()
                    }.also { job ->
                        job.invokeOnCompletion {
                            if (it !is CancellationException) {
                                logger.debug { "completed" }
                                onComplete()
                            }
                        }
                    }
            logger.debug { "started" }
            notifier(false, null)
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        logger.debug { "stopped" }
    }

    private fun finishDownload(
        logger: KLogger,
        download: DownloadChapter,
    ) {
        notifier(true, DownloadUpdate(FINISHED, download))
        downloadQueue -= download
        onDownloadFinished()
        logger.debug { "finished" }
    }

    private suspend fun run() {
        while (downloadQueue.isNotEmpty() && currentCoroutineContext().isActive) {
            val download =
                availableSourceDownloads.firstOrNull {
                    (it.state == Queued || it.state == Finished || (it.state == Error && it.tries < 3)) // 3 re-tries per download
                } ?: break

            val logContext = "${logger.name} - downloadChapter($download))"
            val downloadLogger = KotlinLogging.logger(logContext)

            downloadLogger.debug { "start" }

            // handle cases were the downloader was stopped before the finished download could be removed from the queue
            // otherwise, it will create an endless loop, due to never removing the finished chapter and thinking that the
            // current download chapter was moved down in the queue
            if (download.state == Finished) {
                finishDownload(downloadLogger, download)
                break
            }

            try {
                download.state = Downloading
                step(DownloadUpdate(PROGRESS, download), true)

                download.chapter = getChapterDownloadReadyByIndex(0, download.chapterIndex, download.mangaId)

                ChapterDownloadHelper.download(download.mangaId, download.chapter.id, download, scope) { downloadChapter, immediate ->
                    step(downloadChapter?.let { DownloadUpdate(PROGRESS, downloadChapter) }, immediate)
                }
                download.state = Finished
                transaction {
                    ChapterTable.update(
                        { (ChapterTable.manga eq download.mangaId) and (ChapterTable.sourceOrder eq download.chapterIndex) },
                    ) {
                        it[isDownloaded] = true
                    }
                }
                finishDownload(downloadLogger, download)
            } catch (e: CancellationException) {
                logger.debug { "Downloader was stopped" }
                availableSourceDownloads.filter { it.state == Downloading }.forEach { it.state = Queued }
                notifier(false, DownloadUpdate(STOPPED, download))
            } catch (e: PauseDownloadException) {
                downloadLogger.debug { "paused" }
                download.state = Queued
                notifier(false, DownloadUpdate(PAUSED, download))
            } catch (e: Exception) {
                downloadLogger.warn(e) { "failed due to" }
                download.tries++
                download.state = Error
                notifier(false, DownloadUpdate(ERROR, download))
            }
        }
    }
}
