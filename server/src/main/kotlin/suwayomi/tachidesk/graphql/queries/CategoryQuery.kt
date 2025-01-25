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
import suwayomi.tachidesk.graphql.queries.filter.IntFilter
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
import suwayomi.tachidesk.graphql.types.CategoryNodeList
import suwayomi.tachidesk.graphql.types.CategoryType
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class CategoryQuery {
    fun category(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<CategoryType> {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        return dataFetchingEnvironment.getValueFromDataLoader("CategoryDataLoader", id)
    }

    enum class CategoryOrderBy(
        override val column: Column<*>,
    ) : OrderBy<CategoryType> {
        ID(CategoryTable.id),
        NAME(CategoryTable.name),
        ORDER(CategoryTable.order),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> CategoryTable.id greater cursor.value.toInt()
                NAME -> greaterNotUnique(CategoryTable.name, CategoryTable.id, cursor, String::toString)
                ORDER -> greaterNotUnique(CategoryTable.order, CategoryTable.id, cursor, String::toInt)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> CategoryTable.id less cursor.value.toInt()
                NAME -> lessNotUnique(CategoryTable.name, CategoryTable.id, cursor, String::toString)
                ORDER -> lessNotUnique(CategoryTable.order, CategoryTable.id, cursor, String::toInt)
            }

        override fun asCursor(type: CategoryType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    NAME -> type.id.toString() + "-" + type.name
                    ORDER -> type.id.toString() + "-" + type.order
                }
            return Cursor(value)
        }
    }

    data class CategoryOrder(
        override val by: CategoryOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<CategoryOrderBy>

    data class CategoryCondition(
        val id: Int? = null,
        val order: Int? = null,
        val name: String? = null,
        val default: Boolean? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, CategoryTable.id)
            opAnd.eq(order, CategoryTable.order)
            opAnd.eq(name, CategoryTable.name)
            opAnd.eq(default, CategoryTable.isDefault)

            return opAnd.op
        }
    }

    data class CategoryFilter(
        val id: IntFilter? = null,
        val order: IntFilter? = null,
        val name: StringFilter? = null,
        val default: BooleanFilter? = null,
        override val and: List<CategoryFilter>? = null,
        override val or: List<CategoryFilter>? = null,
        override val not: CategoryFilter? = null,
    ) : Filter<CategoryFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(CategoryTable.id, id),
                andFilterWithCompare(CategoryTable.order, order),
                andFilterWithCompareString(CategoryTable.name, name),
                andFilterWithCompare(CategoryTable.isDefault, default),
            )
    }

    fun categories(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: CategoryCondition? = null,
        filter: CategoryFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: CategoryOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<CategoryOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): CategoryNodeList {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val queryResults =
            transaction {
                val res = CategoryTable.selectAll().where { CategoryTable.user eq userId }

                res.applyOps(condition, filter)

                if (order != null || orderBy != null || (last != null || before != null)) {
                    val baseSort = listOf(CategoryOrder(CategoryOrderBy.ID, SortOrder.ASC))
                    val deprecatedSort = listOfNotNull(orderBy?.let { CategoryOrder(orderBy, orderByType) })
                    val actualSort = (order.orEmpty() + deprecatedSort + baseSort)
                    actualSort.forEach { (orderBy, orderByType) ->
                        val orderByColumn = orderBy.column
                        val orderType = orderByType.maybeSwap(last ?: before)

                        res.orderBy(orderByColumn to orderType)
                    }
                }

                val total = res.count()
                val firstResult = res.firstOrNull()?.get(CategoryTable.id)?.value
                val lastResult = res.lastOrNull()?.get(CategoryTable.id)?.value

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: CategoryOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (CategoryType) -> Cursor = (order?.firstOrNull()?.by ?: CategoryOrderBy.ID)::asCursor

        val resultsAsType = queryResults.results.map { CategoryType(it) }

        return CategoryNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        CategoryNodeList.CategoryEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        CategoryNodeList.CategoryEdge(
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
