package suwayomi.tachidesk.graphql.types

import com.expediagroup.graphql.server.extensions.getValueFromDataLoader
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.ResultRow
import suwayomi.tachidesk.graphql.server.primitives.Cursor
import suwayomi.tachidesk.graphql.server.primitives.Edge
import suwayomi.tachidesk.graphql.server.primitives.Node
import suwayomi.tachidesk.graphql.server.primitives.NodeList
import suwayomi.tachidesk.graphql.server.primitives.PageInfo
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.track.tracker.Tracker
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.manga.model.table.TrackSearchTable
import java.util.concurrent.CompletableFuture

class TrackerType(
    val id: Int,
    val name: String,
    val icon: String,
    val isLoggedIn: Boolean,
    val authUrl: String?,
    val supportsTrackDeletion: Boolean?,
) : Node {
    constructor(tracker: Tracker, userId: Int) : this(
        tracker.isLoggedIn(userId),
        tracker,
    )

    constructor(isLoggedIn: Boolean, tracker: Tracker) : this(
        tracker.id,
        tracker.name,
        Track.proxyThumbnailUrl(tracker.id),
        isLoggedIn,
        if (isLoggedIn) {
            null
        } else {
            tracker.authUrl()
        },
        tracker.supportsTrackDeletion,
    )

    fun statuses(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<List<TrackStatusType>> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, List<TrackStatusType>>("TrackerStatusesDataLoader", id)

    fun scores(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<List<String>> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, List<String>>("TrackerScoresDataLoader", id)

    fun trackRecords(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<TrackRecordNodeList> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, TrackRecordNodeList>("TrackRecordsForTrackerIdDataLoader", id)

    fun isTokenExpired(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<Boolean> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, Boolean>("TrackerTokenExpiredDataLoader", id)
}

class TrackStatusType(
    val value: Int,
    val name: String,
)

class TrackRecordType(
    val id: Int,
    val mangaId: Int,
    val trackerId: Int,
    val remoteId: Long,
    val libraryId: Long?,
    val title: String,
    val lastChapterRead: Double,
    val totalChapters: Int,
    val status: Int,
    val score: Double,
    val remoteUrl: String,
    val startDate: Long,
    val finishDate: Long,
) : Node {
    constructor(row: ResultRow) : this(
        row[TrackRecordTable.id].value,
        row[TrackRecordTable.mangaId].value,
        row[TrackRecordTable.trackerId],
        row[TrackRecordTable.remoteId],
        row[TrackRecordTable.libraryId],
        row[TrackRecordTable.title],
        row[TrackRecordTable.lastChapterRead],
        row[TrackRecordTable.totalChapters],
        row[TrackRecordTable.status],
        row[TrackRecordTable.score],
        row[TrackRecordTable.remoteUrl],
        row[TrackRecordTable.startDate],
        row[TrackRecordTable.finishDate],
    )

    fun displayScore(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<String> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, String>("DisplayScoreForTrackRecordDataLoader", id)

    fun manga(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<MangaType> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, MangaType>("MangaDataLoader", mangaId)

    fun tracker(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<TrackerType> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, TrackerType>("TrackerDataLoader", trackerId)
}

class TrackSearchType(
    val id: Int,
    val trackerId: Int,
    val remoteId: Long,
    val title: String,
    val totalChapters: Int,
    val trackingUrl: String,
    val coverUrl: String,
    val summary: String,
    val publishingStatus: String,
    val publishingType: String,
    val startDate: String,
) {
    constructor(row: ResultRow) : this(
        row[TrackSearchTable.id].value,
        row[TrackSearchTable.trackerId],
        row[TrackSearchTable.remoteId],
        row[TrackSearchTable.title],
        row[TrackSearchTable.totalChapters],
        row[TrackSearchTable.trackingUrl],
        row[TrackSearchTable.coverUrl],
        row[TrackSearchTable.summary],
        row[TrackSearchTable.publishingStatus],
        row[TrackSearchTable.publishingType],
        row[TrackSearchTable.startDate],
    )

    fun tracker(dataFetchingEnvironment: DataFetchingEnvironment): CompletableFuture<TrackerType> =
        dataFetchingEnvironment.getValueFromDataLoader<Int, TrackerType>("TrackerDataLoader", trackerId)
}

data class TrackRecordNodeList(
    override val nodes: List<TrackRecordType>,
    override val edges: List<TrackRecordEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class TrackRecordEdge(
        override val cursor: Cursor,
        override val node: TrackRecordType,
    ) : Edge()

    companion object {
        fun List<TrackRecordType>.toNodeList(): TrackRecordNodeList =
            TrackRecordNodeList(
                nodes = this,
                edges = getEdges(),
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = Cursor(0.toString()),
                        endCursor = Cursor(lastIndex.toString()),
                    ),
                totalCount = size,
            )

        private fun List<TrackRecordType>.getEdges(): List<TrackRecordEdge> {
            if (isEmpty()) return emptyList()
            return listOf(
                TrackRecordEdge(
                    cursor = Cursor("0"),
                    node = first(),
                ),
                TrackRecordEdge(
                    cursor = Cursor(lastIndex.toString()),
                    node = last(),
                ),
            )
        }
    }
}

data class TrackerNodeList(
    override val nodes: List<TrackerType>,
    override val edges: List<TrackerEdge>,
    override val pageInfo: PageInfo,
    override val totalCount: Int,
) : NodeList() {
    data class TrackerEdge(
        override val cursor: Cursor,
        override val node: TrackerType,
    ) : Edge()

    companion object {
        fun List<TrackerType>.toNodeList(): TrackerNodeList =
            TrackerNodeList(
                nodes = this,
                edges = getEdges(),
                pageInfo =
                    PageInfo(
                        hasNextPage = false,
                        hasPreviousPage = false,
                        startCursor = Cursor(0.toString()),
                        endCursor = Cursor(lastIndex.toString()),
                    ),
                totalCount = size,
            )

        private fun List<TrackerType>.getEdges(): List<TrackerEdge> {
            if (isEmpty()) return emptyList()
            return listOf(
                TrackerEdge(
                    cursor = Cursor("0"),
                    node = first(),
                ),
                TrackerEdge(
                    cursor = Cursor(lastIndex.toString()),
                    node = last(),
                ),
            )
        }
    }
}
