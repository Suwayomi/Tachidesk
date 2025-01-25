package suwayomi.tachidesk.manga.impl.update

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.CategoryManga
import suwayomi.tachidesk.manga.impl.Chapter
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.model.dataclass.CategoryDataClass
import suwayomi.tachidesk.manga.model.dataclass.IncludeOrExclude
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.util.HAScheduler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
class Updater : IUpdater {
    private val logger = KotlinLogging.logger {}
    private val notifyFlowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val notifyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val statusFlow = MutableSharedFlow<UpdateStatus>()
    override val status = statusFlow.onStart { emit(getStatus()) }

    init {
        // has to be in its own scope (notifyFlowScope), otherwise, the collection gets canceled due to canceling the scopes (scope) children in the reset function
        notifyFlowScope.launch {
            notifyFlow.sample(1.seconds).collect {
                updateStatus(immediate = true)
            }
        }
    }

    private val _status = MutableStateFlow(UpdateStatus())
    override val statusDeprecated = _status.asStateFlow()

    private var updateStatusCategories: Map<CategoryUpdateStatus, List<CategoryDataClass>> = emptyMap()
    private var updateStatusSkippedMangas: List<MangaDataClass> = emptyList()
    private val tracker = ConcurrentHashMap<Int, UpdateJob>()
    private val updateChannels = ConcurrentHashMap<String, Channel<UpdateJob>>()

    private var maxSourcesInParallel = 20 // max permits, necessary to be set to be able to release up to 20 permits
    private val semaphore = Semaphore(maxSourcesInParallel)

    private val lastUpdateKey = "lastGlobalUpdate"
    private val lastAutomatedUpdateKey = "lastAutomatedGlobalUpdate"
    private val preferences = Injekt.get<Application>().getSharedPreferences("server_util", Context.MODE_PRIVATE)

    private var currentUpdateTaskId = ""

    init {
        serverConfig.subscribeTo(serverConfig.globalUpdateInterval, ::scheduleUpdateTask)
        serverConfig.subscribeTo(
            serverConfig.maxSourcesInParallel,
            { value ->
                val newMaxPermits = value.coerceAtLeast(1).coerceAtMost(20)
                val permitDifference = maxSourcesInParallel - newMaxPermits
                maxSourcesInParallel = newMaxPermits

                val addMorePermits = permitDifference < 0
                for (i in 1..permitDifference.absoluteValue) {
                    if (addMorePermits) {
                        semaphore.release()
                    } else {
                        semaphore.acquire()
                    }
                }
            },
            ignoreInitialValue = false,
        )
    }

    override fun getLastUpdateTimestamp(): Long = preferences.getLong(lastUpdateKey, 0)

    private fun autoUpdateTask() {
        try {
            val lastAutomatedUpdate = preferences.getLong(lastAutomatedUpdateKey, 0)
            preferences.edit().putLong(lastAutomatedUpdateKey, System.currentTimeMillis()).apply()

            if (getStatus().running) {
                logger.debug { "Global update is already in progress" }
                return
            }

            logger.info {
                "Trigger global update (interval= ${serverConfig.globalUpdateInterval.value}h, lastAutomatedUpdate= ${Date(
                    lastAutomatedUpdate,
                )})"
            }
            // todo User accounts
            addCategoriesToUpdateQueue(Category.getCategoryList(1), clear = true, forceAll = false)
        } catch (e: Exception) {
            logger.error(e) { "autoUpdateTask: failed due to" }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleUpdateTask() {
        HAScheduler.deschedule(currentUpdateTaskId)

        val isAutoUpdateDisabled = serverConfig.globalUpdateInterval.value == 0.0
        if (isAutoUpdateDisabled) {
            return
        }

        val updateInterval =
            serverConfig.globalUpdateInterval.value.hours
                .coerceAtLeast(6.hours)
                .inWholeMilliseconds
        val lastAutomatedUpdate = preferences.getLong(lastAutomatedUpdateKey, 0)
        val timeToNextExecution = (updateInterval - (System.currentTimeMillis() - lastAutomatedUpdate)).mod(updateInterval)

        val wasPreviousUpdateTriggered =
            System.currentTimeMillis() - (
                if (lastAutomatedUpdate > 0) lastAutomatedUpdate else System.currentTimeMillis()
            ) < updateInterval
        if (!wasPreviousUpdateTriggered) {
            GlobalScope.launch {
                autoUpdateTask()
            }
        }

        HAScheduler.schedule(::autoUpdateTask, updateInterval, timeToNextExecution, "global-update")
    }

    private fun getStatus(running: Boolean? = null): UpdateStatus {
        val jobs = tracker.values.toList()
        val isRunning =
            running
                ?: jobs.any { job ->
                    job.status == JobStatus.PENDING || job.status == JobStatus.RUNNING
                }
        return UpdateStatus(this.updateStatusCategories, jobs, this.updateStatusSkippedMangas, isRunning)
    }

    /**
     * Pass "isRunning" to force a specific running state
     */
    private suspend fun updateStatus(
        immediate: Boolean = false,
        isRunning: Boolean? = null,
    ) {
        if (immediate) {
            val status = getStatus(running = isRunning)

            statusFlow.emit(status)
            _status.update { status }

            return
        }

        notifyFlow.emit(Unit)
    }

    private fun getOrCreateUpdateChannelFor(source: String): Channel<UpdateJob> =
        updateChannels.getOrPut(source) {
            logger.debug { "getOrCreateUpdateChannelFor: created channel for $source - channels: ${updateChannels.size + 1}" }
            createUpdateChannel(source)
        }

    private fun createUpdateChannel(source: String): Channel<UpdateJob> {
        val channel = Channel<UpdateJob>(Channel.UNLIMITED)
        channel
            .consumeAsFlow()
            .onEach { job ->
                semaphore.withPermit {
                    process(job)
                }
            }.catch {
                logger.error(it) { "Error during updates (source: $source)" }
                handleChannelUpdateFailure(source)
            }.onCompletion { updateChannels.remove(source) }
            .launchIn(scope)
        return channel
    }

    private suspend fun handleChannelUpdateFailure(source: String) {
        val isFailedSourceUpdate = { job: UpdateJob ->
            val isForSource = job.manga.sourceId == source
            val hasFailed = job.status == JobStatus.FAILED

            isForSource && hasFailed
        }

        // fail all updates for source
        tracker
            .filter { (_, job) -> !isFailedSourceUpdate(job) }
            .forEach { (mangaId, job) ->
                tracker[mangaId] = job.copy(status = JobStatus.FAILED)
            }

        updateStatus()
    }

    private suspend fun process(job: UpdateJob) {
        tracker[job.manga.id] = job.copy(status = JobStatus.RUNNING)
        updateStatus()

        tracker[job.manga.id] =
            try {
                logger.info { "Updating ${job.manga}" }
                if (serverConfig.updateMangas.value || !job.manga.initialized) {
                    Manga.getManga(0, job.manga.id, true)
                }
                Chapter.getChapterList(0, job.manga.id, true)
                job.copy(status = JobStatus.COMPLETE)
            } catch (e: Exception) {
                logger.error(e) { "Error while updating ${job.manga}" }
                if (e is CancellationException) throw e
                job.copy(status = JobStatus.FAILED)
            }

        val wasLastJob = tracker.values.none { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING }

        // in case this is the last update job, the running flag has to be true, before it gets set to false, to be able
        // to properly clear the dataloader store in UpdateType
        updateStatus(immediate = wasLastJob, isRunning = true)

        if (wasLastJob) {
            updateStatus(isRunning = false)
        }
    }

    override fun addCategoriesToUpdateQueue(
        categories: List<CategoryDataClass>,
        clear: Boolean?,
        forceAll: Boolean,
    ) {
        preferences.edit().putLong(lastUpdateKey, System.currentTimeMillis()).apply()

        if (clear == true) {
            reset()
        }

        val includeInUpdateStatusToCategoryMap = categories.groupBy { it.includeInUpdate }
        val excludedCategories = includeInUpdateStatusToCategoryMap[IncludeOrExclude.EXCLUDE].orEmpty()
        val includedCategories = includeInUpdateStatusToCategoryMap[IncludeOrExclude.INCLUDE].orEmpty()
        val unsetCategories = includeInUpdateStatusToCategoryMap[IncludeOrExclude.UNSET].orEmpty()
        val categoriesToUpdate =
            if (forceAll) {
                categories
            } else {
                includedCategories.ifEmpty { unsetCategories }
            }
        val skippedCategories = categories.subtract(categoriesToUpdate.toSet()).toList()
        val updateStatusCategories =
            mapOf(
                Pair(CategoryUpdateStatus.UPDATING, categoriesToUpdate),
                Pair(CategoryUpdateStatus.SKIPPED, skippedCategories),
            )

        logger.debug { "Updating categories: '${categoriesToUpdate.joinToString("', '") { it.name }}'" }

        val categoriesToUpdateMangas =
            categoriesToUpdate
                .flatMap { CategoryManga.getCategoryMangaList(1, it.id) } // todo User accounts
                .distinctBy { it.id }
        val mangasToCategoriesMap = CategoryManga.getMangasCategories(1, categoriesToUpdateMangas.map { it.id }) // todo User accounts
        val mangasToUpdate =
            categoriesToUpdateMangas
                .asSequence()
                .filter { it.updateStrategy == UpdateStrategy.ALWAYS_UPDATE }
                .filter {
                    if (serverConfig.excludeUnreadChapters.value) {
                        (it.unreadCount ?: 0L) == 0L
                    } else {
                        true
                    }
                }.filter {
                    if (it.initialized && serverConfig.excludeNotStarted.value) {
                        it.lastReadAt != null
                    } else {
                        true
                    }
                }.filter {
                    if (serverConfig.excludeCompleted.value) {
                        it.status != MangaStatus.COMPLETED.name
                    } else {
                        true
                    }
                }.filter { forceAll || !excludedCategories.any { category -> mangasToCategoriesMap[it.id]?.contains(category) == true } }
                .toList()
        val skippedMangas = categoriesToUpdateMangas.subtract(mangasToUpdate.toSet()).toList()

        this.updateStatusCategories = updateStatusCategories
        this.updateStatusSkippedMangas = skippedMangas

        if (mangasToUpdate.isEmpty()) {
            // In case no manga gets updated and no update job was running before, the client would never receive an info
            // about its update request
            scope.launch {
                updateStatus(immediate = true)
            }
            return
        }

        addMangasToQueue(
            mangasToUpdate
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, MangaDataClass::title)),
        )
    }

    override fun addMangasToQueue(mangas: List<MangaDataClass>) {
        // create all manga update jobs before adding them to the queue so that the client is able to calculate the
        // progress properly right form the start
        mangas.forEach { tracker[it.id] = UpdateJob(it) }
        mangas.forEach { addMangaToQueue(it) }
    }

    private fun addMangaToQueue(manga: MangaDataClass) {
        val updateChannel = getOrCreateUpdateChannelFor(manga.sourceId)
        scope.launch {
            updateChannel.send(UpdateJob(manga))
        }
    }

    override fun reset() {
        scope.coroutineContext.cancelChildren()
        tracker.clear()
        this.updateStatusCategories = emptyMap()
        this.updateStatusSkippedMangas = emptyList()
        scope.launch {
            updateStatus(immediate = true, isRunning = false)
        }
        updateChannels.forEach { (_, channel) -> channel.cancel() }
        updateChannels.clear()
    }
}
