/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.queries

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.OpAnd
import suwayomi.tachidesk.graphql.queries.filter.StringFilter
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareString
import suwayomi.tachidesk.graphql.queries.filter.applyOps
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Order
import suwayomi.tachidesk.graphql.server.primitives.OrderBy
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.graphql.server.primitives.QueryResults
import suwayomi.tachidesk.graphql.server.primitives.applyBeforeAfter
import suwayomi.tachidesk.graphql.server.primitives.greaterNotUnique
import suwayomi.tachidesk.graphql.server.primitives.lessNotUnique
import suwayomi.tachidesk.graphql.server.primitives.maybeSwap
import suwayomi.tachidesk.graphql.types.GlobalMetaNodeList
import suwayomi.tachidesk.graphql.types.GlobalMetaType
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class MetaQuery {
    fun meta(
        dataFetchingEnvironment: DataFetchingEnvironment,
        key: String,
    ): CompletableFuture<GlobalMetaType> {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        return dataFetchingEnvironment.getValueFromDataLoader("GlobalMetaDataLoader", key)
    }

    enum class MetaOrderBy(
        override val column: Column<*>,
    ) : OrderBy<GlobalMetaType> {
        KEY(GlobalMetaTable.key),
        VALUE(GlobalMetaTable.value),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                KEY -> GlobalMetaTable.key greater cursor.value
                VALUE -> greaterNotUnique(GlobalMetaTable.value, GlobalMetaTable.key, cursor, String::toString)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                KEY -> GlobalMetaTable.key less cursor.value
                VALUE -> lessNotUnique(GlobalMetaTable.value, GlobalMetaTable.key, cursor, String::toString)
            }

        override fun asCursor(type: GlobalMetaType): Cursor {
            val value =
                when (this) {
                    KEY -> type.key
                    VALUE -> type.key + "\\-" + type.value
                }
            return Cursor(value)
        }
    }

    data class MetaOrder(
        override val by: MetaOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<MetaOrderBy>

    data class MetaCondition(
        val key: String? = null,
        val value: String? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(key, GlobalMetaTable.key)
            opAnd.eq(value, GlobalMetaTable.value)

            return opAnd.op
        }
    }

    data class MetaFilter(
        val key: StringFilter? = null,
        val value: StringFilter? = null,
        override val and: List<MetaFilter>? = null,
        override val or: List<MetaFilter>? = null,
        override val not: MetaFilter? = null,
    ) : Filter<MetaFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareString(GlobalMetaTable.key, key),
                andFilterWithCompareString(GlobalMetaTable.value, value),
            )
    }

    fun metas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: MetaCondition? = null,
        filter: MetaFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: MetaOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<MetaOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): GlobalMetaNodeList {
        val userId = dataFetchingEnvironment.graphQlContext.getAttribute(Attribute.TachideskUser).requireUser()
        val queryResults =
            transaction {
                val res = GlobalMetaTable.selectAll().where { GlobalMetaTable.user eq userId }

                res.applyOps(condition, filter)

                if (order != null || orderBy != null || (last != null || before != null)) {
                    val baseSort = listOf(MetaOrder(MetaOrderBy.KEY, SortOrder.ASC))
                    val deprecatedSort = listOfNotNull(orderBy?.let { MetaOrder(orderBy, orderByType) })
                    val actualSort = (order.orEmpty() + deprecatedSort + baseSort)
                    actualSort.forEach { (orderBy, orderByType) ->
                        val orderByColumn = orderBy.column
                        val orderType = orderByType.maybeSwap(last ?: before)

                        res.orderBy(orderByColumn to orderType)
                    }
                }

                val total = res.count()
                val firstResult = res.firstOrNull()?.get(GlobalMetaTable.key)
                val lastResult = res.lastOrNull()?.get(GlobalMetaTable.key)

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: MetaOrderBy.KEY,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (GlobalMetaType) -> Cursor = (order?.firstOrNull()?.by ?: MetaOrderBy.KEY)::asCursor

        val resultsAsType = queryResults.results.map { GlobalMetaType(it) }

        return GlobalMetaNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        GlobalMetaNodeList.MetaEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        GlobalMetaNodeList.MetaEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey != resultsAsType.lastOrNull()?.key,
                    hasPreviousPage = queryResults.firstKey != resultsAsType.firstOrNull()?.key,
                    startCursor = resultsAsType.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = resultsAsType.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = queryResults.total.toInt(),
        )
    }
}
