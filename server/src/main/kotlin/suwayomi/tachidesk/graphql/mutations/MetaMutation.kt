package suwayomi.tachidesk.graphql.mutations

import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.global.impl.GlobalMeta
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.GlobalMetaType
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser

class MetaMutation {
    data class SetGlobalMetaInput(
        val clientMutationId: String? = null,
        val meta: GlobalMetaType,
    )

    data class SetGlobalMetaPayload(
        val clientMutationId: String?,
        val meta: GlobalMetaType,
    )

    fun setGlobalMeta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SetGlobalMetaInput,
    ): DataFetcherResult<SetGlobalMetaPayload?> {
        val (clientMutationId, meta) = input

        return asDataFetcherResult {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            GlobalMeta.modifyMeta(userId, meta.key, meta.value)

            SetGlobalMetaPayload(clientMutationId, meta)
        }
    }

    data class DeleteGlobalMetaInput(
        val clientMutationId: String? = null,
        val key: String,
    )

    data class DeleteGlobalMetaPayload(
        val clientMutationId: String?,
        val meta: GlobalMetaType?,
    )

    fun deleteGlobalMeta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: DeleteGlobalMetaInput,
    ): DataFetcherResult<DeleteGlobalMetaPayload?> {
        val (clientMutationId, key) = input

        return asDataFetcherResult {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            val meta =
                transaction {
                    val meta =
                        GlobalMetaTable
                            .selectAll()
                            .where { GlobalMetaTable.key eq key and (GlobalMetaTable.user eq userId) }
                            .firstOrNull()

                    GlobalMetaTable.deleteWhere { GlobalMetaTable.key eq key and (GlobalMetaTable.user eq userId) }

                    if (meta != null) {
                        GlobalMetaType(meta)
                    } else {
                        null
                    }
                }

            DeleteGlobalMetaPayload(clientMutationId, meta)
        }
    }
}
