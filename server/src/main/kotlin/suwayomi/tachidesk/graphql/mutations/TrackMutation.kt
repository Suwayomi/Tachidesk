package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.graphql.asDataFetcherResult
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.TrackRecordType
import suwayomi.tachidesk.graphql.types.TrackerType
import suwayomi.tachidesk.manga.impl.track.Track
import suwayomi.tachidesk.manga.impl.track.tracker.TrackerManager
import suwayomi.tachidesk.manga.model.table.TrackRecordTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser
import java.util.concurrent.CompletableFuture

class TrackMutation {
    data class LoginTrackerOAuthInput(
        val clientMutationId: String? = null,
        val trackerId: Int,
        val callbackUrl: String,
    )

    data class LoginTrackerOAuthPayload(
        val clientMutationId: String?,
        val isLoggedIn: Boolean,
        val tracker: TrackerType,
    )

    fun loginTrackerOAuth(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LoginTrackerOAuthInput,
    ): CompletableFuture<LoginTrackerOAuthPayload> {
        val tracker =
            requireNotNull(TrackerManager.getTracker(input.trackerId)) {
                "Could not find tracker"
            }
        return future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            tracker.authCallback(userId, input.callbackUrl)
            val trackerType = TrackerType(tracker, userId)
            LoginTrackerOAuthPayload(
                input.clientMutationId,
                trackerType.isLoggedIn,
                trackerType,
            )
        }
    }

    data class LoginTrackerCredentialsInput(
        val clientMutationId: String? = null,
        val trackerId: Int,
        val username: String,
        val password: String,
    )

    data class LoginTrackerCredentialsPayload(
        val clientMutationId: String?,
        val isLoggedIn: Boolean,
        val tracker: TrackerType,
    )

    fun loginTrackerCredentials(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LoginTrackerCredentialsInput,
    ): CompletableFuture<LoginTrackerCredentialsPayload> {
        val tracker =
            requireNotNull(TrackerManager.getTracker(input.trackerId)) {
                "Could not find tracker"
            }
        return future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            tracker.login(userId, input.username, input.password)
            val trackerType = TrackerType(tracker, userId)
            LoginTrackerCredentialsPayload(
                input.clientMutationId,
                trackerType.isLoggedIn,
                trackerType,
            )
        }
    }

    data class LogoutTrackerInput(
        val clientMutationId: String? = null,
        val trackerId: Int,
    )

    data class LogoutTrackerPayload(
        val clientMutationId: String?,
        val isLoggedIn: Boolean,
        val tracker: TrackerType,
    )

    fun logoutTracker(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: LogoutTrackerInput,
    ): CompletableFuture<LogoutTrackerPayload> {
        val tracker =
            requireNotNull(TrackerManager.getTracker(input.trackerId)) {
                "Could not find tracker"
            }
        return future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            require(tracker.isLoggedIn(userId)) {
                "Cannot logout of a tracker that is not logged-in"
            }
            tracker.logout(userId)
            val trackerType = TrackerType(tracker, userId)
            LogoutTrackerPayload(
                input.clientMutationId,
                trackerType.isLoggedIn,
                trackerType,
            )
        }
    }

    data class BindTrackInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        val trackerId: Int,
        val remoteId: Long,
    )

    data class BindTrackPayload(
        val clientMutationId: String?,
        val trackRecord: TrackRecordType,
    )

    fun bindTrack(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: BindTrackInput,
    ): CompletableFuture<BindTrackPayload> {
        val (clientMutationId, mangaId, trackerId, remoteId) = input

        return future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            Track.bind(
                userId,
                mangaId,
                trackerId,
                remoteId,
            )
            val trackRecord =
                transaction {
                    TrackRecordTable
                        .selectAll()
                        .where {
                            TrackRecordTable.mangaId eq mangaId and
                                (TrackRecordTable.trackerId eq trackerId) and
                                (TrackRecordTable.user eq userId)
                        }.first()
                }
            BindTrackPayload(
                clientMutationId,
                TrackRecordType(trackRecord),
            )
        }
    }

    data class FetchTrackInput(
        val clientMutationId: String? = null,
        val recordId: Int,
    )

    data class FetchTrackPayload(
        val clientMutationId: String?,
        val trackRecord: TrackRecordType,
    )

    fun fetchTrack(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: FetchTrackInput,
    ): CompletableFuture<FetchTrackPayload> {
        val (clientMutationId, recordId) = input

        return future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            Track.refresh(userId, recordId)
            val trackRecord =
                transaction {
                    TrackRecordTable
                        .selectAll()
                        .where {
                            TrackRecordTable.id eq recordId and
                                (TrackRecordTable.user eq userId)
                        }.first()
                }
            FetchTrackPayload(
                clientMutationId,
                TrackRecordType(trackRecord),
            )
        }
    }

    data class UnbindTrackInput(
        val clientMutationId: String? = null,
        val recordId: Int,
        @GraphQLDescription("This will only work if the tracker of the track record supports deleting tracks")
        val deleteRemoteTrack: Boolean? = null,
    )

    data class UnbindTrackPayload(
        val clientMutationId: String?,
        val trackRecord: TrackRecordType?,
    )

    fun unbindTrack(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UnbindTrackInput,
    ): CompletableFuture<UnbindTrackPayload> {
        val (clientMutationId, recordId, deleteRemoteTrack) = input

        return future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            Track.unbind(userId, recordId, deleteRemoteTrack)
            val trackRecord =
                transaction {
                    TrackRecordTable
                        .selectAll()
                        .where {
                            TrackRecordTable.id eq recordId and
                                (TrackRecordTable.user eq userId)
                        }.firstOrNull()
                }
            UnbindTrackPayload(
                clientMutationId,
                trackRecord?.let { TrackRecordType(it) },
            )
        }
    }

    data class TrackProgressInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
    )

    data class TrackProgressPayload(
        val clientMutationId: String?,
        val trackRecords: List<TrackRecordType>,
    )

    fun trackProgress(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: TrackProgressInput,
    ): CompletableFuture<DataFetcherResult<TrackProgressPayload?>> {
        val (clientMutationId, mangaId) = input

        return future {
            asDataFetcherResult {
                val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
                Track.trackChapter(userId, mangaId)
                val trackRecords =
                    transaction {
                        TrackRecordTable
                            .selectAll()
                            .where {
                                TrackRecordTable.mangaId eq mangaId and
                                    (TrackRecordTable.user eq userId)
                            }.toList()
                    }
                TrackProgressPayload(
                    clientMutationId,
                    trackRecords.map { TrackRecordType(it) },
                )
            }
        }
    }

    data class UpdateTrackInput(
        val clientMutationId: String? = null,
        val recordId: Int,
        val status: Int? = null,
        val lastChapterRead: Double? = null,
        val scoreString: String? = null,
        val startDate: Long? = null,
        val finishDate: Long? = null,
        @GraphQLDeprecated("Replaced with \"unbindTrack\" mutation", replaceWith = ReplaceWith("unbindTrack"))
        val unbind: Boolean? = null,
    )

    data class UpdateTrackPayload(
        val clientMutationId: String?,
        val trackRecord: TrackRecordType?,
    )

    fun updateTrack(
        dataFetchingEnvironment: DataFetchingEnvironment,
        input: UpdateTrackInput,
    ): CompletableFuture<UpdateTrackPayload> =
        future {
            val userId = dataFetchingEnvironment.getAttribute(Attribute.TachideskUser).requireUser()
            Track.update(
                userId,
                Track.UpdateInput(
                    input.recordId,
                    input.status,
                    input.lastChapterRead,
                    input.scoreString,
                    input.startDate,
                    input.finishDate,
                    input.unbind,
                ),
            )

            val trackRecord =
                transaction {
                    TrackRecordTable
                        .selectAll()
                        .where {
                            TrackRecordTable.id eq input.recordId and
                                (TrackRecordTable.user eq userId)
                        }.firstOrNull()
                }
            UpdateTrackPayload(
                input.clientMutationId,
                trackRecord?.let { TrackRecordType(it) },
            )
        }
}
