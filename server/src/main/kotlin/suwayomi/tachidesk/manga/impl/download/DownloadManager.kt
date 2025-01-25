package suwayomi.tachidesk.manga.impl.download

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.websocket.WsContext
import io.javalin.websocket.WsMessageContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Downloading
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Error
import suwayomi.tachidesk.manga.impl.download.model.DownloadState.Queued
import suwayomi.tachidesk.manga.impl.download.model.DownloadStatus
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdate
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdateType
import suwayomi.tachidesk.manga.impl.download.model.DownloadUpdates
import suwayomi.tachidesk.manga.impl.download.model.Status
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.reflect.jvm.jvmName
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@OptIn(FlowPreview::class)
object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = ConcurrentHashMap<String, WsContext>()
    private val downloadQueue = CopyOnWriteArrayList<DownloadChapter>()
    private val downloadUpdates = CopyOnWriteArraySet<DownloadUpdate>()
    private val downloaders = ConcurrentHashMap<String, Downloader>()

    private const val DOWNLOAD_QUEUE_KEY = "downloadQueueKey"
    private val sharedPreferences =
        Injekt.get<Application>().getSharedPreferences(DownloadManager::class.jvmName, Context.MODE_PRIVATE)

    private fun loadDownloadQueue(): List<Int> =
        sharedPreferences
            .getStringSet(DOWNLOAD_QUEUE_KEY, emptySet())
            ?.mapNotNull {
                it.toInt()
            }.orEmpty()

    private fun saveDownloadQueue() {
        sharedPreferences
            .edit()
            .putStringSet(DOWNLOAD_QUEUE_KEY, downloadQueue.map { it.chapter.id.toString() }.toSet())
            .apply()
    }

    private fun triggerSaveDownloadQueue() {
        scope.launch { saveQueueFlow.emit(Unit) }
    }

    private fun handleDownloadUpdate(
        immediate: Boolean,
        download: DownloadUpdate? = null,
    ) {
        notifyAllClients(immediate, listOfNotNull(download))
    }

    fun restoreAndResumeDownloads() {
        scope.launch {
            logger.debug { "restoreAndResumeDownloads: Restore download queue..." }
            enqueue(EnqueueInput(loadDownloadQueue()))

            if (downloadQueue.size > 0) {
                logger.info { "restoreAndResumeDownloads: Restored download queue, starting downloads..." }
            }
        }
    }

    fun addClient(ctx: WsContext) {
        clients[ctx.sessionId()] = ctx
    }

    fun removeClient(ctx: WsContext) {
        clients.remove(ctx.sessionId())
    }

    fun notifyClient(ctx: WsContext) {
        ctx.send(
            getStatus(),
        )
    }

    fun handleRequest(ctx: WsMessageContext) {
        when (ctx.message()) {
            "STATUS" -> notifyClient(ctx)
            else ->
                ctx.send(
                    """
                        |Invalid command.
                        |Supported commands are:
                        |    - STATUS
                        |       sends the current download status
                        |
                    """.trimMargin(),
                )
        }
    }

    private val notifyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Deprecated("Replaced with updatesFlow", replaceWith = ReplaceWith("updatesFlow"))
    private val statusFlow = MutableSharedFlow<DownloadStatus>()

    @Deprecated("Replaced with updates", replaceWith = ReplaceWith("updates"))
    val status = statusFlow.onStart { emit(getStatus()) }

    private val updatesFlow = MutableSharedFlow<DownloadUpdates>()
    val updates = updatesFlow.onStart { emit(getDownloadUpdates(addInitial = true)) }

    init {
        scope.launch {
            notifyFlow.sample(1.seconds).collect {
                notifyAllClients(immediate = true)
            }
        }
    }

    private val saveQueueFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        saveQueueFlow.onEach { saveDownloadQueue() }.launchIn(scope)
    }

    private fun sendStatusToAllClients(status: DownloadStatus) {
        clients.forEach {
            it.value.send(status)
        }
    }

    private fun notifyAllClients(
        immediate: Boolean = false,
        downloads: List<DownloadUpdate> = emptyList(),
    ) {
        downloadUpdates.addAll(downloads)

        if (immediate) {
            val status = getStatus()
            val updates = getDownloadUpdates()

            downloadUpdates.clear()

            scope.launch {
                statusFlow.emit(status)
                updatesFlow.emit(updates)
                sendStatusToAllClients(status)
            }

            return
        }

        scope.launch {
            notifyFlow.emit(Unit)
        }
    }

    fun getStatus(): DownloadStatus =
        DownloadStatus(
            if (downloadQueue.none { it.state == Downloading }) {
                Status.Stopped
            } else {
                Status.Started
            },
            downloadQueue.toList(),
        )

    private fun getDownloadUpdates(addInitial: Boolean = false): DownloadUpdates =
        DownloadUpdates(
            if (downloadQueue.none { it.state == Downloading }) {
                Status.Stopped
            } else {
                Status.Started
            },
            downloadUpdates.toList(),
            if (addInitial) downloadQueue.toList() else null,
        )

    private val downloaderWatch = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        serverConfig.subscribeTo(serverConfig.maxSourcesInParallel, { maxSourcesInParallel ->
            val runningDownloaders = downloaders.values.filter { it.isActive }
            var downloadersToStop = runningDownloaders.size - maxSourcesInParallel

            logger.debug { "Max sources in parallel changed to $maxSourcesInParallel (running downloaders ${runningDownloaders.size})" }

            if (downloadersToStop > 0) {
                runningDownloaders.takeWhile {
                    it.stop()
                    --downloadersToStop > 0
                }
            } else {
                downloaderWatch.emit(Unit)
            }
        })

        scope.launch {
            downloaderWatch.sample(1.seconds).collect {
                val runningDownloaders = downloaders.values.filter { it.isActive }
                val availableDownloads = downloadQueue.filter { it.state != Error }

                logger.info {
                    "Running: ${runningDownloaders.size}, " +
                        "Queued: ${availableDownloads.size}, " +
                        "Failed: ${downloadQueue.size - availableDownloads.size}"
                }

                if (runningDownloaders.size < serverConfig.maxSourcesInParallel.value.coerceAtLeast(1)) {
                    availableDownloads
                        .asSequence()
                        .map { it.manga.sourceId }
                        .distinct()
                        .minus(
                            runningDownloaders.map { it.sourceId }.toSet(),
                        ).take((serverConfig.maxSourcesInParallel.value - runningDownloaders.size).coerceAtLeast(0))
                        .map { getDownloader(it) }
                        .forEach {
                            it.start()
                        }
                    notifyAllClients()
                }
            }
        }
    }

    private fun refreshDownloaders() {
        scope.launch {
            downloaderWatch.emit(Unit)
        }
    }

    private fun getDownloader(sourceId: String) =
        downloaders.getOrPut(sourceId) {
            Downloader(
                scope = scope,
                sourceId = sourceId,
                downloadQueue = downloadQueue,
                notifier = ::handleDownloadUpdate,
                onComplete = ::refreshDownloaders,
                onDownloadFinished = ::triggerSaveDownloadQueue,
            )
        }

    fun enqueueWithChapterIndex(
        mangaId: Int,
        chapterIndex: Int,
    ) {
        val chapter =
            transaction {
                ChapterTable
                    .select(ChapterTable.id)
                    .where { ChapterTable.manga.eq(mangaId) and ChapterTable.sourceOrder.eq(chapterIndex) }
                    .first()
            }
        enqueue(EnqueueInput(chapterIds = listOf(chapter[ChapterTable.id].value)))
    }

    @Serializable
    // Input might have additional formats in the future, such as "All for mangaID" or "Unread for categoryID"
    // Having this input format is just future-proofing
    data class EnqueueInput(
        val chapterIds: List<Int>?,
    )

    fun enqueue(input: EnqueueInput) {
        if (input.chapterIds.isNullOrEmpty()) return

        val chapters =
            transaction {
                (ChapterTable innerJoin MangaTable)
                    .selectAll()
                    .where { ChapterTable.id inList input.chapterIds }
                    .orderBy(ChapterTable.manga)
                    .orderBy(ChapterTable.sourceOrder)
                    .toList()
            }

        // todo User accounts
        val mangas =
            transaction {
                chapters
                    .distinctBy { chapter -> chapter[MangaTable.id] }
                    .map { MangaTable.toDataClass(0, it) }
                    .associateBy { it.id }
            }

        // todo User accounts
        val inputPairs =
            transaction {
                chapters.map {
                    Pair(
                        // this should be safe because mangas is created above from chapters
                        mangas[it[ChapterTable.manga].value]!!,
                        ChapterTable.toDataClass(0, it),
                    )
                }
            }

        addMultipleToQueue(inputPairs)
    }

    /**
     * Tries to add multiple inputs to queue
     * If any of inputs was actually added to queue, starts the queue
     */
    private fun addMultipleToQueue(inputs: List<Pair<MangaDataClass, ChapterDataClass>>) {
        val addedChapters = inputs.mapNotNull { addToQueue(it.first, it.second) }
        if (addedChapters.isNotEmpty()) {
            start()
            notifyAllClients(false, addedChapters.map { DownloadUpdate(DownloadUpdateType.QUEUED, it) })
        }
        scope.launch {
            downloaderWatch.emit(Unit)
        }
    }

    /**
     * Tries to add chapter to queue.
     * If chapter is added, returns the created DownloadChapter, otherwise returns null
     */
    private fun addToQueue(
        manga: MangaDataClass,
        chapter: ChapterDataClass,
    ): DownloadChapter? {
        val downloadChapter = downloadQueue.firstOrNull { it.mangaId == manga.id && it.chapterIndex == chapter.index }

        val addToQueue = downloadChapter == null
        if (addToQueue) {
            val newDownloadChapter =
                DownloadChapter(
                    chapter.index,
                    manga.id,
                    chapter,
                    manga,
                    downloadQueue.size,
                )
            downloadQueue.add(newDownloadChapter)
            triggerSaveDownloadQueue()
            logger.debug { "Added chapter ${chapter.id} to download queue ($newDownloadChapter)" }
            return newDownloadChapter
        }

        val retryDownload = downloadChapter?.state == Error
        if (retryDownload) {
            logger.debug { "Chapter ${chapter.id} download failed, retry download ($downloadChapter)" }

            downloadChapter?.state = Queued
            downloadChapter?.progress = 0f

            return downloadChapter
        }

        logger.debug { "Chapter ${chapter.id} already present in queue ($downloadChapter)" }
        return null
    }

    fun dequeue(input: EnqueueInput) {
        if (input.chapterIds.isNullOrEmpty()) return
        dequeue(downloadQueue.filter { it.chapter.id in input.chapterIds }.toSet())
    }

    fun dequeue(
        chapterIndex: Int,
        mangaId: Int,
    ) {
        dequeue(downloadQueue.filter { it.mangaId == mangaId && it.chapterIndex == chapterIndex }.toSet())
    }

    fun dequeue(
        mangaIds: List<Int>,
        chaptersToIgnore: List<Int> = emptyList(),
    ) {
        dequeue(downloadQueue.filter { it.mangaId in mangaIds && it.chapter.id !in chaptersToIgnore }.toSet())
    }

    private fun dequeue(chapterDownloads: Set<DownloadChapter>) {
        logger.debug { "dequeue ${chapterDownloads.size} chapters [${chapterDownloads.joinToString(separator = ", ") { "$it" }}]" }

        downloadQueue.removeAll(chapterDownloads)
        triggerSaveDownloadQueue()

        notifyAllClients(false, chapterDownloads.toList().map { DownloadUpdate(DownloadUpdateType.DEQUEUED, it) })
    }

    fun reorder(
        chapterIndex: Int,
        mangaId: Int,
        to: Int,
    ) {
        val download =
            downloadQueue.find { it.mangaId == mangaId && it.chapterIndex == chapterIndex }
                ?: return

        reorder(download, to)
    }

    fun reorder(
        chapterId: Int,
        to: Int,
    ) {
        val download =
            downloadQueue.find { it.chapter.id == chapterId }
                ?: return

        reorder(download, to)
    }

    private fun reorder(
        download: DownloadChapter,
        to: Int,
    ) {
        require(to >= 0) { "'to' must be over or equal to 0" }

        logger.debug { "reorder download $download from ${downloadQueue.indexOf(download)} to $to" }

        downloadQueue -= download
        downloadQueue.add(to, download)
        download.position = to
        notifyAllClients(false, listOf(DownloadUpdate(DownloadUpdateType.POSITION, download)))
        triggerSaveDownloadQueue()
    }

    fun start() {
        logger.debug { "start" }

        scope.launch {
            downloaderWatch.emit(Unit)
        }
    }

    suspend fun stop() {
        logger.debug { "stop" }

        coroutineScope {
            downloaders
                .map { (_, downloader) ->
                    async {
                        downloader.stop()
                    }
                }.awaitAll()
        }
        notifyAllClients()
    }

    suspend fun clear() {
        logger.debug { "clear" }

        stop()
        val removedDownloads = downloadQueue.toList().map { DownloadUpdate(DownloadUpdateType.DEQUEUED, it) }
        downloadQueue.clear()
        triggerSaveDownloadQueue()
        notifyAllClients(false, removedDownloads)
    }
}

enum class DownloaderState(
    val state: Int,
) {
    Stopped(0),
    Running(1),
    Paused(2),
}
