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
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.LongFilter
import suwayomi.tachidesk.graphql.queries.filter.OpAnd
import suwayomi.tachidesk.graphql.queries.filter.StringFilter
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompare
import suwayomi.tachidesk.graphql.queries.filter.andFilterWithCompareEntity
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
import suwayomi.tachidesk.graphql.types.SourceNodeList
import suwayomi.tachidesk.graphql.types.SourceType
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class SourceQuery {
    fun source(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Long,
    ): CompletableFuture<SourceType> {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        return dataFetchingEnvironment.getValueFromDataLoader("SourceDataLoader", id)
    }

    enum class SourceOrderBy(
        override val column: Column<*>,
    ) : OrderBy<SourceType> {
        ID(SourceTable.id),
        NAME(SourceTable.name),
        LANG(SourceTable.lang),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> SourceTable.id greater cursor.value.toLong()
                NAME -> greaterNotUnique(SourceTable.name, SourceTable.id, cursor, String::toString)
                LANG -> greaterNotUnique(SourceTable.lang, SourceTable.id, cursor, String::toString)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> SourceTable.id less cursor.value.toLong()
                NAME -> lessNotUnique(SourceTable.name, SourceTable.id, cursor, String::toString)
                LANG -> lessNotUnique(SourceTable.lang, SourceTable.id, cursor, String::toString)
            }

        override fun asCursor(type: SourceType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    NAME -> type.id.toString() + "-" + type.name
                    LANG -> type.id.toString() + "-" + type.lang
                }
            return Cursor(value)
        }
    }

    data class SourceOrder(
        override val by: SourceOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<SourceOrderBy>

    data class SourceCondition(
        val id: Long? = null,
        val name: String? = null,
        val lang: String? = null,
        val isNsfw: Boolean? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, SourceTable.id)
            opAnd.eq(name, SourceTable.name)
            opAnd.eq(lang, SourceTable.lang)
            opAnd.eq(isNsfw, SourceTable.isNsfw)

            return opAnd.op
        }
    }

    data class SourceFilter(
        val id: LongFilter? = null,
        val name: StringFilter? = null,
        val lang: StringFilter? = null,
        val isNsfw: BooleanFilter? = null,
        override val and: List<SourceFilter>? = null,
        override val or: List<SourceFilter>? = null,
        override val not: SourceFilter? = null,
    ) : Filter<SourceFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(SourceTable.id, id),
                andFilterWithCompareString(SourceTable.name, name),
                andFilterWithCompareString(SourceTable.lang, lang),
                andFilterWithCompare(SourceTable.isNsfw, isNsfw),
            )
    }

    fun sources(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: SourceCondition? = null,
        filter: SourceFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: SourceOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<SourceOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): SourceNodeList {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val (queryResults, resultsAsType) =
            transaction {
                val res = SourceTable.selectAll()

                res.applyOps(condition, filter)

                if (order != null || orderBy != null || (last != null || before != null)) {
                    val baseSort = listOf(SourceOrder(SourceOrderBy.ID, SortOrder.ASC))
                    val deprecatedSort = listOfNotNull(orderBy?.let { SourceOrder(orderBy, orderByType) })
                    val actualSort = (order.orEmpty() + deprecatedSort + baseSort)
                    actualSort.forEach { (orderBy, orderByType) ->
                        val orderByColumn = orderBy.column
                        val orderType = orderByType.maybeSwap(last ?: before)

                        res.orderBy(orderByColumn to orderType)
                    }
                }

                val total = res.count()
                val firstResult = res.firstOrNull()?.get(SourceTable.id)?.value
                val lastResult = res.lastOrNull()?.get(SourceTable.id)?.value

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: SourceOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList()).let {
                    it to it.results.mapNotNull { SourceType(it) }
                }
            }

        val getAsCursor: (SourceType) -> Cursor = (order?.firstOrNull()?.by ?: SourceOrderBy.ID)::asCursor

        return SourceNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        SourceNodeList.SourceEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        SourceNodeList.SourceEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey != resultsAsType.lastOrNull()?.id,
                    hasPreviousPage = queryResults.firstKey != resultsAsType.firstOrNull()?.id,
                    startCursor = resultsAsType.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = resultsAsType.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = queryResults.total.toInt(),
        )
    }
}
