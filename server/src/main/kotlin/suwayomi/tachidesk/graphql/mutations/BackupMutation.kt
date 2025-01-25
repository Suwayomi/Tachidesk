package suwayomi.tachidesk.graphql.mutations

import graphql.schema.DataFetchingEnvironment
import io.javalin.http.UploadedFile
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import suwayomi.tachidesk.graphql.server.TemporaryFileStorage
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.BackupRestoreStatus
import suwayomi.tachidesk.graphql.types.toStatus
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupImport
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class BackupMutation {
    data class RestoreBackupInput(
        val clientMutationId: String? = null,
        val backup: UploadedFile,
    )

    data class RestoreBackupPayload(
        val clientMutationId: String?,
        val id: String,
        val status: BackupRestoreStatus?,
    )

    @OptIn(DelicateCoroutinesApi::class)
    fun restoreBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: RestoreBackupInput,
    ): CompletableFuture<RestoreBackupPayload> {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val (clientMutationId, backup) = input

        return future {
            val restoreId = ProtoBackupImport.restore(userId, backup.content())

            withTimeout(10.seconds) {
                ProtoBackupImport.notifyFlow.first {
                    ProtoBackupImport.getRestoreState(restoreId) != null
                }
            }

            RestoreBackupPayload(clientMutationId, restoreId, ProtoBackupImport.getRestoreState(restoreId)?.toStatus())
        }
    }

    data class CreateBackupInput(
        val clientMutationId: String? = null,
        val includeChapters: Boolean? = null,
        val includeCategories: Boolean? = null,
    )

    data class CreateBackupPayload(
        val clientMutationId: String?,
        val url: String,
    )

    fun createBackup(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: CreateBackupInput? = null,
    ): CreateBackupPayload {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val filename = Backup.getFilename()

        val backup =
            ProtoBackupExport.createBackup(
                userId,
                BackupFlags(
                    includeManga = true,
                    includeCategories = input?.includeCategories ?: true,
                    includeChapters = input?.includeChapters ?: true,
                    includeTracking = true,
                    includeHistory = true,
                ),
            )

        TemporaryFileStorage.saveFile(filename, backup)

        return CreateBackupPayload(
            clientMutationId = input?.clientMutationId,
            url = "/api/graphql/files/backup/$filename",
        )
    }
}
