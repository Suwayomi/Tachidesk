package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.UpdateStatus
import suwayomi.tachidesk.manga.impl.Category
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class UpdateMutation {
    private val updater: IUpdater by injectLazy()

    data class UpdateLibraryMangaInput(
        val clientMutationId: String? = null,
    )

    data class UpdateLibraryMangaPayload(
        val clientMutationId: String?,
        val updateStatus: UpdateStatus,
    )

    fun updateLibraryManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateLibraryMangaInput,
    ): CompletableFuture<DataFetcherResult<UpdateLibraryMangaPayload?>> =
        future {
            asDataFetcherResult {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                updater.addCategoriesToUpdateQueue(
                    Category.getCategoryList(userId),
                    clear = true,
                    forceAll = false,
                )

                UpdateLibraryMangaPayload(
                    input.clientMutationId,
                    updateStatus =
                        withTimeout(30.seconds) {
                            UpdateStatus(updater.status.first())
                        },
                )
            }
        }

    data class UpdateCategoryMangaInput(
        val clientMutationId: String? = null,
        val categories: List<Int>,
    )

    data class UpdateCategoryMangaPayload(
        val clientMutationId: String?,
        val updateStatus: UpdateStatus,
    )

    fun updateCategoryManga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateCategoryMangaInput,
    ): CompletableFuture<DataFetcherResult<UpdateCategoryMangaPayload?>> =
        future {
            asDataFetcherResult {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                val categories =
                    transaction {
                        CategoryTable
                            .selectAll()
                            .where {
                                CategoryTable.id inList input.categories and (CategoryTable.user eq userId)
                            }.map {
                                CategoryTable.toDataClass(it)
                            }
                    }
                updater.addCategoriesToUpdateQueue(categories, clear = true, forceAll = true)

                UpdateCategoryMangaPayload(
                    input.clientMutationId,
                    updateStatus =
                        withTimeout(30.seconds) {
                            UpdateStatus(updater.status.first())
                        },
                )
            }
        }

    data class UpdateStopInput(
        val clientMutationId: String? = null,
    )

    data class UpdateStopPayload(
        val clientMutationId: String?,
    )

    fun updateStop(input: UpdateStopInput): UpdateStopPayload {
        updater.reset()
        return UpdateStopPayload(input.clientMutationId)
    }
}
