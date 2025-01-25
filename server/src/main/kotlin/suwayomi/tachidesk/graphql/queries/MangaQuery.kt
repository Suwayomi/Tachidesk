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
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.queries.filter.BooleanFilter
import suwayomi.tachidesk.graphql.queries.filter.ComparableScalarFilter
import suwayomi.tachidesk.graphql.queries.filter.Filter
import suwayomi.tachidesk.graphql.queries.filter.HasGetOp
import suwayomi.tachidesk.graphql.queries.filter.IntFilter
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
import suwayomi.tachidesk.graphql.types.MangaNodeList
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.MangaStatus
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class MangaQuery {
    fun manga(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<MangaType> {
        dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        return dataFetchingEnvironment.getValueFromDataLoader("MangaDataLoader", id)
    }

    enum class MangaOrderBy(
        override val column: Column<*>,
    ) : OrderBy<MangaType> {
        ID(MangaTable.id),
        TITLE(MangaTable.title),
        IN_LIBRARY_AT(MangaUserTable.inLibraryAt),
        LAST_FETCHED_AT(MangaTable.lastFetchedAt),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> MangaTable.id greater cursor.value.toInt()
                TITLE -> greaterNotUnique(MangaTable.title, MangaTable.id, cursor, String::toString)
                IN_LIBRARY_AT -> greaterNotUnique(MangaUserTable.inLibraryAt, MangaTable.id, cursor, String::toLong)
                LAST_FETCHED_AT -> greaterNotUnique(MangaTable.lastFetchedAt, MangaTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> MangaTable.id less cursor.value.toInt()
                TITLE -> lessNotUnique(MangaTable.title, MangaTable.id, cursor, String::toString)
                IN_LIBRARY_AT -> lessNotUnique(MangaUserTable.inLibraryAt, MangaTable.id, cursor, String::toLong)
                LAST_FETCHED_AT -> lessNotUnique(MangaTable.lastFetchedAt, MangaTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: MangaType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    TITLE -> type.id.toString() + "-" + type.title
                    IN_LIBRARY_AT -> type.id.toString() + "-" + type.inLibraryAt.toString()
                    LAST_FETCHED_AT -> type.id.toString() + "-" + type.lastFetchedAt.toString()
                }
            return Cursor(value)
        }
    }

    data class MangaOrder(
        override val by: MangaOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<MangaOrderBy>

    data class MangaCondition(
        val id: Int? = null,
        val sourceId: Long? = null,
        val url: String? = null,
        val title: String? = null,
        val thumbnailUrl: String? = null,
        val initialized: Boolean? = null,
        val artist: String? = null,
        val author: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: MangaStatus? = null,
        val inLibrary: Boolean? = null,
        val inLibraryAt: Long? = null,
        val realUrl: String? = null,
        val lastFetchedAt: Long? = null,
        val chaptersLastFetchedAt: Long? = null,
        val categoryIds: List<Int>? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, MangaTable.id)
            opAnd.eq(sourceId, MangaTable.sourceReference)
            opAnd.eq(url, MangaTable.url)
            opAnd.eq(title, MangaTable.title)
            opAnd.eq(thumbnailUrl, MangaTable.thumbnail_url)
            opAnd.eq(initialized, MangaTable.initialized)
            opAnd.eq(artist, MangaTable.artist)
            opAnd.eq(author, MangaTable.author)
            opAnd.eq(description, MangaTable.description)
            opAnd.andWhereAll(genre) { MangaTable.genre like "%$it%" }
            opAnd.eq(status?.value, MangaTable.status)
            opAnd.eq(inLibrary, MangaUserTable.inLibrary)
            opAnd.eq(inLibraryAt, MangaUserTable.inLibraryAt)
            opAnd.eq(realUrl, MangaTable.realUrl)
            opAnd.eq(lastFetchedAt, MangaTable.lastFetchedAt)
            opAnd.eq(chaptersLastFetchedAt, MangaTable.chaptersLastFetchedAt)
            opAnd.andWhere(categoryIds) { CategoryMangaTable.category inList it }

            return opAnd.op
        }
    }

    data class MangaStatusFilter(
        override val isNull: Boolean? = null,
        override val equalTo: MangaStatus? = null,
        override val notEqualTo: MangaStatus? = null,
        override val notEqualToAll: List<MangaStatus>? = null,
        override val notEqualToAny: List<MangaStatus>? = null,
        override val distinctFrom: MangaStatus? = null,
        override val distinctFromAll: List<MangaStatus>? = null,
        override val distinctFromAny: List<MangaStatus>? = null,
        override val notDistinctFrom: MangaStatus? = null,
        override val `in`: List<MangaStatus>? = null,
        override val notIn: List<MangaStatus>? = null,
        override val lessThan: MangaStatus? = null,
        override val lessThanOrEqualTo: MangaStatus? = null,
        override val greaterThan: MangaStatus? = null,
        override val greaterThanOrEqualTo: MangaStatus? = null,
    ) : ComparableScalarFilter<MangaStatus> {
        fun asIntFilter() =
            IntFilter(
                equalTo = equalTo?.value,
                notEqualTo = notEqualTo?.value,
                notEqualToAll = notEqualToAll?.map { it.value },
                notEqualToAny = notEqualToAny?.map { it.value },
                distinctFrom = distinctFrom?.value,
                distinctFromAll = distinctFromAll?.map { it.value },
                distinctFromAny = distinctFromAny?.map { it.value },
                notDistinctFrom = notDistinctFrom?.value,
                `in` = `in`?.map { it.value },
                notIn = notIn?.map { it.value },
                lessThan = lessThan?.value,
                lessThanOrEqualTo = lessThanOrEqualTo?.value,
                greaterThan = greaterThan?.value,
                greaterThanOrEqualTo = greaterThanOrEqualTo?.value,
            )
    }

    data class MangaFilter(
        val id: IntFilter? = null,
        val sourceId: LongFilter? = null,
        val url: StringFilter? = null,
        val title: StringFilter? = null,
        val thumbnailUrl: StringFilter? = null,
        val initialized: BooleanFilter? = null,
        val artist: StringFilter? = null,
        val author: StringFilter? = null,
        val description: StringFilter? = null,
        val genre: StringFilter? = null,
        val status: MangaStatusFilter? = null,
        val inLibrary: BooleanFilter? = null,
        val inLibraryAt: LongFilter? = null,
        val realUrl: StringFilter? = null,
        val lastFetchedAt: LongFilter? = null,
        val chaptersLastFetchedAt: LongFilter? = null,
        val categoryId: IntFilter? = null,
        override val and: List<MangaFilter>? = null,
        override val or: List<MangaFilter>? = null,
        override val not: MangaFilter? = null,
    ) : Filter<MangaFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(MangaTable.id, id),
                andFilterWithCompare(MangaTable.sourceReference, sourceId),
                andFilterWithCompareString(MangaTable.url, url),
                andFilterWithCompareString(MangaTable.title, title),
                andFilterWithCompareString(MangaTable.thumbnail_url, thumbnailUrl),
                andFilterWithCompare(MangaTable.initialized, initialized),
                andFilterWithCompareString(MangaTable.artist, artist),
                andFilterWithCompareString(MangaTable.author, author),
                andFilterWithCompareString(MangaTable.description, description),
                andFilterWithCompareString(MangaTable.genre, genre),
                andFilterWithCompare(MangaTable.status, status?.asIntFilter()),
                andFilterWithCompare(MangaUserTable.inLibrary, inLibrary),
                andFilterWithCompare(MangaUserTable.inLibraryAt, inLibraryAt),
                andFilterWithCompareString(MangaTable.realUrl, realUrl),
                andFilterWithCompare(MangaTable.lastFetchedAt, lastFetchedAt),
                andFilterWithCompare(MangaTable.chaptersLastFetchedAt, chaptersLastFetchedAt),
                andFilterWithCompareEntity(CategoryMangaTable.category, categoryId),
            )
    }

    fun mangas(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: MangaCondition? = null,
        filter: MangaFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: MangaOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<MangaOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): MangaNodeList {
        val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
        val queryResults =
            transaction {
                val res =
                    MangaTable
                        .getWithUserData(userId)
                        .leftJoin(CategoryMangaTable)
                        .select(MangaTable.columns)
                        .where { CategoryMangaTable.user eq userId }
                        .withDistinctOn(MangaTable.id)

                res.applyOps(condition, filter)

                if (order != null || orderBy != null || (last != null || before != null)) {
                    val baseSort = listOf(MangaOrder(MangaOrderBy.ID, SortOrder.ASC))
                    val deprecatedSort = listOfNotNull(orderBy?.let { MangaOrder(orderBy, orderByType) })
                    val actualSort = (order.orEmpty() + deprecatedSort + baseSort)
                    actualSort.forEach { (orderBy, orderByType) ->
                        val orderByColumn = orderBy.column
                        val orderType = orderByType.maybeSwap(last ?: before)

                        res.orderBy(orderByColumn to orderType)
                    }
                }

                val total = res.count()
                val firstResult = res.firstOrNull()?.get(MangaTable.id)?.value
                val lastResult = res.lastOrNull()?.get(MangaTable.id)?.value

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: MangaOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (MangaType) -> Cursor = (order?.firstOrNull()?.by ?: MangaOrderBy.ID)::asCursor

        val resultsAsType = queryResults.results.map { MangaType(it) }

        return MangaNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        MangaNodeList.MangaEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        MangaNodeList.MangaEdge(
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
