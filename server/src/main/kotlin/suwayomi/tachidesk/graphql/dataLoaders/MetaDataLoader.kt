package suwayomi.tachidesk.graphql.dataLoaders

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import graphql.GraphQLContext
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import suwayomi.tachidesk.graphql.server.getAttribute
import suwayomi.tachidesk.graphql.types.CategoryMetaType
import suwayomi.tachidesk.graphql.types.ChapterMetaType
import suwayomi.tachidesk.graphql.types.GlobalMetaType
import suwayomi.tachidesk.graphql.types.MangaMetaType
import suwayomi.tachidesk.graphql.types.SourceMetaType
import suwayomi.tachidesk.manga.model.table.CategoryMetaTable
import suwayomi.tachidesk.manga.model.table.ChapterMetaTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.SourceMetaTable
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.future
import suwayomi.tachidesk.server.user.requireUser

class GlobalMetaDataLoader : KotlinDataLoader<String, GlobalMetaType?> {
    override val dataLoaderName = "GlobalMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<String, GlobalMetaType?> =
        DataLoaderFactory.newDataLoader<String, GlobalMetaType?> { ids ->
            future {
                val userId = graphQLContext.getAttribute(Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        GlobalMetaTable
                            .selectAll()
                            .where { GlobalMetaTable.key inList ids and (GlobalMetaTable.user eq userId) }
                            .map { GlobalMetaType(it) }
                            .associateBy { it.key }
                    ids.map { metasByRefId[it] }
                }
            }
        }
}

class ChapterMetaDataLoader : KotlinDataLoader<Int, List<ChapterMetaType>> {
    override val dataLoaderName = "ChapterMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<ChapterMetaType>> =
        DataLoaderFactory.newDataLoader<Int, List<ChapterMetaType>> { ids ->
            future {
                val userId = graphQLContext.getAttribute(Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        ChapterMetaTable
                            .selectAll()
                            .where { ChapterMetaTable.ref inList ids and (ChapterMetaTable.user eq userId) }
                            .map { ChapterMetaType(it) }
                            .groupBy { it.chapterId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

class MangaMetaDataLoader : KotlinDataLoader<Int, List<MangaMetaType>> {
    override val dataLoaderName = "MangaMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<MangaMetaType>> =
        DataLoaderFactory.newDataLoader<Int, List<MangaMetaType>> { ids ->
            future {
                val userId = graphQLContext.getAttribute(Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        MangaMetaTable
                            .selectAll()
                            .where { MangaMetaTable.ref inList ids and (MangaMetaTable.user eq userId) }
                            .map { MangaMetaType(it) }
                            .groupBy { it.mangaId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

class CategoryMetaDataLoader : KotlinDataLoader<Int, List<CategoryMetaType>> {
    override val dataLoaderName = "CategoryMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Int, List<CategoryMetaType>> =
        DataLoaderFactory.newDataLoader<Int, List<CategoryMetaType>> { ids ->
            future {
                val userId = graphQLContext.getAttribute(Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        CategoryMetaTable
                            .selectAll()
                            .where { CategoryMetaTable.ref inList ids and (CategoryMetaTable.user eq userId) }
                            .map { CategoryMetaType(it) }
                            .groupBy { it.categoryId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}

class SourceMetaDataLoader : KotlinDataLoader<Long, List<SourceMetaType>> {
    override val dataLoaderName = "SourceMetaDataLoader"

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<Long, List<SourceMetaType>> =
        DataLoaderFactory.newDataLoader<Long, List<SourceMetaType>> { ids ->
            future {
                val userId = graphQLContext.getAttribute(Attribute.TachideskUser).requireUser()
                transaction {
                    addLogger(Slf4jSqlDebugLogger)
                    val metasByRefId =
                        SourceMetaTable
                            .selectAll()
                            .where { SourceMetaTable.ref inList ids and (SourceMetaTable.user eq userId) }
                            .map { SourceMetaType(it) }
                            .groupBy { it.sourceId }
                    ids.map { metasByRefId[it].orEmpty() }
                }
            }
        }
}
