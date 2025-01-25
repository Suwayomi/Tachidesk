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
import suwayomi.tachidesk.graphql.queries.filter.DoubleFilter
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
import suwayomi.tachidesk.graphql.types.TrackRecordNodeList
import suwayomi.tachidesk.graphql.types.TrackRecordType
import suwayomi.tachidesk.graphql.types.TrackSearchType
import suwayomi.tachidesk.graphql.types.TrackerNodeList
import suwayomi.tachidesk.graphql.types.TrackerType
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.insertAll
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class TrackQuery {
    fun tracker(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<TrackerType> = dataFetchingEnvironment.getValueFromDataLoader<Int, TrackerType>("TrackerDataLoader", id)

    enum class TrackerOrderBy {
        ID,
        NAME,
        IS_LOGGED_IN,
        ;

        fun greater(
            tracker: TrackerType,
            cursor: Cursor,
        ): Boolean =
            when (this) {
                ID -> tracker.id > cursor.value.toInt()
                NAME -> tracker.name > cursor.value
                IS_LOGGED_IN -> {
                    val value = cursor.value.substringAfter('-').toBooleanStrict()
                    !value || tracker.isLoggedIn
                }
            }

        fun less(
            tracker: TrackerType,
            cursor: Cursor,
        ): Boolean =
            when (this) {
                ID -> tracker.id < cursor.value.toInt()
                NAME -> tracker.name < cursor.value
                IS_LOGGED_IN -> {
                    val value = cursor.value.substringAfter('-').toBooleanStrict()
                    value || !tracker.isLoggedIn
                }
            }

        fun asCursor(type: TrackerType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    NAME -> type.name
                    IS_LOGGED_IN -> type.id.toString() + "-" + type.isLoggedIn
                }
            return Cursor(value)
        }
    }

    data class TrackerOrder(
        val by: TrackerOrderBy,
        val byType: SortOrder? = null,
    )

    data class TrackerCondition(
        val id: Int? = null,
        val name: String? = null,
        val icon: String? = null,
        val isLoggedIn: Boolean? = null,
    )

    data class TrackerFilter(
        val id: IntFilter? = null,
        val name: StringFilter? = null,
        val icon: StringFilter? = null,
        val isLoggedIn: BooleanFilter? = null,
        val authUrl: StringFilter? = null,
        val and: List<TrackerFilter>? = null,
        val or: List<TrackerFilter>? = null,
        val not: TrackerFilter? = null,
    )

    fun trackers(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: TrackerCondition? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: TrackerOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<TrackerOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): TrackerNodeList {
        val (queryResults, resultsAsType) =
            run {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                var res = TrackerManager.services.map { TrackerType(it, userId) }

                if (condition != null) {
                    res =
                        res.filter { tracker ->
                            (condition.id == null || (condition.id == tracker.id)) &&
                                (condition.name == null || (condition.name == tracker.name)) &&
                                (condition.icon == null || (condition.icon == tracker.icon)) &&
                                (condition.isLoggedIn == null || (condition.isLoggedIn == tracker.isLoggedIn))
                        }
                }

                if (order != null || orderBy != null || (last != null || before != null)) {
                    val baseSort = listOf(TrackerOrder(TrackerOrderBy.ID, SortOrder.ASC))
                    val deprecatedSort = listOfNotNull(orderBy?.let { TrackerOrder(orderBy, orderByType) })
                    val actualSort = (order.orEmpty() + deprecatedSort + baseSort)
                    actualSort.forEach { (orderBy, orderByType) ->
                        val orderType = orderByType.maybeSwap(last ?: before)

                        res =
                            when (orderType) {
                                SortOrder.DESC, SortOrder.DESC_NULLS_FIRST, SortOrder.DESC_NULLS_LAST ->
                                    when (orderBy) {
                                        TrackerOrderBy.ID -> res.sortedByDescending { it.id }
                                        TrackerOrderBy.NAME -> res.sortedByDescending { it.name }
                                        TrackerOrderBy.IS_LOGGED_IN -> res.sortedByDescending { it.isLoggedIn }
                                    }
                                SortOrder.ASC, SortOrder.ASC_NULLS_FIRST, SortOrder.ASC_NULLS_LAST ->
                                    when (orderBy) {
                                        TrackerOrderBy.ID -> res.sortedBy { it.id }
                                        TrackerOrderBy.NAME -> res.sortedBy { it.name }
                                        TrackerOrderBy.IS_LOGGED_IN -> res.sortedBy { it.isLoggedIn }
                                    }
                            }
                    }
                }

                val total = res.size
                val firstResult = res.firstOrNull()
                val lastResult = res.lastOrNull()

                val realOrderBy = order?.firstOrNull()?.by ?: TrackerOrderBy.ID
                if (after != null) {
                    res =
                        res.filter {
                            when (order?.firstOrNull()?.byType) {
                                SortOrder.DESC, SortOrder.DESC_NULLS_FIRST, SortOrder.DESC_NULLS_LAST -> realOrderBy.less(it, after)
                                null, SortOrder.ASC, SortOrder.ASC_NULLS_FIRST, SortOrder.ASC_NULLS_LAST -> realOrderBy.greater(it, after)
                            }
                        }
                } else if (before != null) {
                    res =
                        res.filter {
                            when (order?.firstOrNull()?.byType) {
                                SortOrder.DESC, SortOrder.DESC_NULLS_FIRST, SortOrder.DESC_NULLS_LAST -> realOrderBy.greater(it, before)
                                null, SortOrder.ASC, SortOrder.ASC_NULLS_FIRST, SortOrder.ASC_NULLS_LAST -> realOrderBy.less(it, before)
                            }
                        }
                }

                if (first != null) {
                    res = res.drop(offset ?: 0).take(first)
                } else if (last != null) {
                    res = res.take(last)
                }

                QueryResults(total.toLong(), firstResult, lastResult, emptyList()) to res
            }

        val getAsCursor: (TrackerType) -> Cursor = (order?.firstOrNull()?.by ?: TrackerOrderBy.ID)::asCursor

        return TrackerNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        TrackerNodeList.TrackerEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        TrackerNodeList.TrackerEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                )
            },
            pageInfo =
                PageInfo(
                    hasNextPage = queryResults.lastKey?.id != resultsAsType.lastOrNull()?.id,
                    hasPreviousPage = queryResults.firstKey?.id != resultsAsType.firstOrNull()?.id,
                    startCursor = resultsAsType.firstOrNull()?.let { getAsCursor(it) },
                    endCursor = resultsAsType.lastOrNull()?.let { getAsCursor(it) },
                ),
            totalCount = queryResults.total.toInt(),
        )
    }

    fun trackRecord(
        dataFetchingEnvironment: DataFetchingEnvironment,
        id: Int,
    ): CompletableFuture<TrackRecordType> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, TrackRecordType>("TrackRecordDataLoader", id)

    enum class TrackRecordOrderBy(
        override val column: Column<*>,
    ) : OrderBy<TrackRecordType> {
        ID(TrackRecordTable.id),
        MANGA_ID(TrackRecordTable.mangaId),
        TRACKER_ID(TrackRecordTable.trackerId),
        REMOTE_ID(TrackRecordTable.remoteId),
        TITLE(TrackRecordTable.title),
        LAST_CHAPTER_READ(TrackRecordTable.lastChapterRead),
        TOTAL_CHAPTERS(TrackRecordTable.lastChapterRead),
        SCORE(TrackRecordTable.score),
        START_DATE(TrackRecordTable.startDate),
        FINISH_DATE(TrackRecordTable.finishDate),
        ;

        override fun greater(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> TrackRecordTable.id greater cursor.value.toInt()
                MANGA_ID -> greaterNotUnique(TrackRecordTable.mangaId, TrackRecordTable.id, cursor)
                TRACKER_ID -> greaterNotUnique(TrackRecordTable.trackerId, TrackRecordTable.id, cursor, String::toInt)
                REMOTE_ID -> greaterNotUnique(TrackRecordTable.remoteId, TrackRecordTable.id, cursor, String::toLong)
                TITLE -> greaterNotUnique(TrackRecordTable.title, TrackRecordTable.id, cursor, String::toString)
                LAST_CHAPTER_READ -> greaterNotUnique(TrackRecordTable.lastChapterRead, TrackRecordTable.id, cursor, String::toDouble)
                TOTAL_CHAPTERS -> greaterNotUnique(TrackRecordTable.totalChapters, TrackRecordTable.id, cursor, String::toInt)
                SCORE -> greaterNotUnique(TrackRecordTable.score, TrackRecordTable.id, cursor, String::toDouble)
                START_DATE -> greaterNotUnique(TrackRecordTable.startDate, TrackRecordTable.id, cursor, String::toLong)
                FINISH_DATE -> greaterNotUnique(TrackRecordTable.finishDate, TrackRecordTable.id, cursor, String::toLong)
            }

        override fun less(cursor: Cursor): Op<Boolean> =
            when (this) {
                ID -> TrackRecordTable.id less cursor.value.toInt()
                MANGA_ID -> lessNotUnique(TrackRecordTable.mangaId, TrackRecordTable.id, cursor)
                TRACKER_ID -> lessNotUnique(TrackRecordTable.trackerId, TrackRecordTable.id, cursor, String::toInt)
                REMOTE_ID -> lessNotUnique(TrackRecordTable.remoteId, TrackRecordTable.id, cursor, String::toLong)
                TITLE -> lessNotUnique(TrackRecordTable.title, TrackRecordTable.id, cursor, String::toString)
                LAST_CHAPTER_READ -> lessNotUnique(TrackRecordTable.lastChapterRead, TrackRecordTable.id, cursor, String::toDouble)
                TOTAL_CHAPTERS -> lessNotUnique(TrackRecordTable.totalChapters, TrackRecordTable.id, cursor, String::toInt)
                SCORE -> lessNotUnique(TrackRecordTable.score, TrackRecordTable.id, cursor, String::toDouble)
                START_DATE -> lessNotUnique(TrackRecordTable.startDate, TrackRecordTable.id, cursor, String::toLong)
                FINISH_DATE -> lessNotUnique(TrackRecordTable.finishDate, TrackRecordTable.id, cursor, String::toLong)
            }

        override fun asCursor(type: TrackRecordType): Cursor {
            val value =
                when (this) {
                    ID -> type.id.toString()
                    MANGA_ID -> type.id.toString() + "-" + type.mangaId
                    TRACKER_ID -> type.id.toString() + "-" + type.trackerId
                    REMOTE_ID -> type.id.toString() + "-" + type.remoteId
                    TITLE -> type.id.toString() + "-" + type.title
                    LAST_CHAPTER_READ -> type.id.toString() + "-" + type.lastChapterRead
                    TOTAL_CHAPTERS -> type.id.toString() + "-" + type.totalChapters
                    SCORE -> type.id.toString() + "-" + type.score
                    START_DATE -> type.id.toString() + "-" + type.startDate
                    FINISH_DATE -> type.id.toString() + "-" + type.finishDate
                }
            return Cursor(value)
        }
    }

    data class TrackRecordOrder(
        override val by: TrackRecordOrderBy,
        override val byType: SortOrder? = null,
    ) : Order<TrackRecordOrderBy>

    data class TrackRecordCondition(
        val id: Int? = null,
        val mangaId: Int? = null,
        val trackerId: Int? = null,
        val remoteId: Long? = null,
        val libraryId: Long? = null,
        val title: String? = null,
        val lastChapterRead: Double? = null,
        val totalChapters: Int? = null,
        val status: Int? = null,
        val score: Double? = null,
        val remoteUrl: String? = null,
        val startDate: Long? = null,
        val finishDate: Long? = null,
    ) : HasGetOp {
        override fun getOp(): Op<Boolean>? {
            val opAnd = OpAnd()
            opAnd.eq(id, TrackRecordTable.id)
            opAnd.eq(mangaId, TrackRecordTable.mangaId)
            opAnd.eq(trackerId, TrackRecordTable.trackerId)
            opAnd.eq(remoteId, TrackRecordTable.remoteId)
            opAnd.eq(libraryId, TrackRecordTable.libraryId)
            opAnd.eq(title, TrackRecordTable.title)
            opAnd.eq(lastChapterRead, TrackRecordTable.lastChapterRead)
            opAnd.eq(totalChapters, TrackRecordTable.totalChapters)
            opAnd.eq(status, TrackRecordTable.status)
            opAnd.eq(score, TrackRecordTable.score)
            opAnd.eq(remoteUrl, TrackRecordTable.remoteUrl)
            opAnd.eq(startDate, TrackRecordTable.startDate)
            opAnd.eq(finishDate, TrackRecordTable.finishDate)

            return opAnd.op
        }
    }

    data class TrackRecordFilter(
        val id: IntFilter? = null,
        val mangaId: IntFilter? = null,
        val trackerId: IntFilter? = null,
        val remoteId: LongFilter? = null,
        val libraryId: LongFilter? = null,
        val title: StringFilter? = null,
        val lastChapterRead: DoubleFilter? = null,
        val totalChapters: IntFilter? = null,
        val status: IntFilter? = null,
        val score: DoubleFilter? = null,
        val remoteUrl: StringFilter? = null,
        val startDate: LongFilter? = null,
        val finishDate: LongFilter? = null,
        override val and: List<TrackRecordFilter>? = null,
        override val or: List<TrackRecordFilter>? = null,
        override val not: TrackRecordFilter? = null,
    ) : Filter<TrackRecordFilter> {
        override fun getOpList(): List<Op<Boolean>> =
            listOfNotNull(
                andFilterWithCompareEntity(TrackRecordTable.id, id),
                andFilterWithCompareEntity(TrackRecordTable.mangaId, mangaId),
                andFilterWithCompare(TrackRecordTable.trackerId, trackerId),
                andFilterWithCompare(TrackRecordTable.remoteId, remoteId),
                andFilterWithCompare(TrackRecordTable.libraryId, libraryId),
                andFilterWithCompareString(TrackRecordTable.title, title),
                andFilterWithCompare(TrackRecordTable.lastChapterRead, lastChapterRead),
                andFilterWithCompare(TrackRecordTable.totalChapters, totalChapters),
                andFilterWithCompare(TrackRecordTable.status, status),
                andFilterWithCompare(TrackRecordTable.score, score),
                andFilterWithCompareString(TrackRecordTable.remoteUrl, remoteUrl),
                andFilterWithCompare(TrackRecordTable.startDate, startDate),
                andFilterWithCompare(TrackRecordTable.finishDate, finishDate),
            )
    }

    fun trackRecords(
        dataFetchingEnvironment: DataFetchingEnvironment,
        condition: TrackRecordCondition? = null,
        filter: TrackRecordFilter? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderBy: TrackRecordOrderBy? = null,
        @GraphQLDeprecated(
            "Replaced with order",
            replaceWith = ReplaceWith("order"),
        )
        orderByType: SortOrder? = null,
        order: List<TrackRecordOrder>? = null,
        before: Cursor? = null,
        after: Cursor? = null,
        first: Int? = null,
        last: Int? = null,
        offset: Int? = null,
    ): TrackRecordNodeList {
        val queryResults =
            transaction {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                val res = TrackRecordTable.selectAll().where { TrackRecordTable.user eq userId }

                res.applyOps(condition, filter)

                if (order != null || orderBy != null || (last != null || before != null)) {
                    val baseSort = listOf(TrackRecordOrder(TrackRecordOrderBy.ID, SortOrder.ASC))
                    val deprecatedSort = listOfNotNull(orderBy?.let { TrackRecordOrder(orderBy, orderByType) })
                    val actualSort = (order.orEmpty() + deprecatedSort + baseSort)
                    actualSort.forEach { (orderBy, orderByType) ->
                        val orderByColumn = orderBy.column
                        val orderType = orderByType.maybeSwap(last ?: before)

                        res.orderBy(orderByColumn to orderType)
                    }
                }

                val total = res.count()
                val firstResult = res.firstOrNull()?.get(TrackRecordTable.id)?.value
                val lastResult = res.lastOrNull()?.get(TrackRecordTable.id)?.value

                res.applyBeforeAfter(
                    before = before,
                    after = after,
                    orderBy = order?.firstOrNull()?.by ?: TrackRecordOrderBy.ID,
                    orderByType = order?.firstOrNull()?.byType,
                )

                if (first != null) {
                    res.limit(first).offset(offset?.toLong() ?: 0)
                } else if (last != null) {
                    res.limit(last)
                }

                QueryResults(total, firstResult, lastResult, res.toList())
            }

        val getAsCursor: (TrackRecordType) -> Cursor = (order?.firstOrNull()?.by ?: TrackRecordOrderBy.ID)::asCursor

        val resultsAsType = queryResults.results.map { TrackRecordType(it) }

        return TrackRecordNodeList(
            resultsAsType,
            if (resultsAsType.isEmpty()) {
                emptyList()
            } else {
                listOfNotNull(
                    resultsAsType.firstOrNull()?.let {
                        TrackRecordNodeList.TrackRecordEdge(
                            getAsCursor(it),
                            it,
                        )
                    },
                    resultsAsType.lastOrNull()?.let {
                        TrackRecordNodeList.TrackRecordEdge(
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

    data class SearchTrackerInput(
        val trackerId: Int,
        val query: String,
    )

    data class SearchTrackerPayload(
        val trackSearches: List<TrackSearchType>,
    )

    fun searchTracker(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: SearchTrackerInput,
    ): CompletableFuture<SearchTrackerPayload> =
        future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            val tracker =
                requireNotNull(TrackerManager.getTracker(input.trackerId)) {
                    "Tracker not found"
                }
            require(tracker.isLoggedIn(userId)) {
                "Tracker needs to be logged-in to search"
            }
            SearchTrackerPayload(
                tracker.search(userId, input.query).insertAll().map {
                    TrackSearchType(it)
                },
            )
        }
}
