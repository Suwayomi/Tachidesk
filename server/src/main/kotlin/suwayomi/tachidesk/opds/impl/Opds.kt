package suwayomi.tachidesk.opds.impl

import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.Manga.getManga
import suwayomi.tachidesk.manga.impl.MangaList.proxyThumbnailUrl
import suwayomi.tachidesk.manga.impl.Source
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.model.dataclass.ChapterDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaDataClass
import suwayomi.tachidesk.manga.model.dataclass.OpdsDataClass
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Opds {
    private const val ITEMS_PER_PAGE = 20

    fun getRootFeed(baseUrl: String): String {
        val formattedNow = opdsDateFormatter.format(Instant.now())
        val sources =
            transaction {
                SourceTable
                    .selectAll()
                    .orderBy(SourceTable.name to SortOrder.ASC)
                    .mapNotNull { Source.getSource(it[SourceTable.id].value) }
                    .filter { sourceHasMangasWithChapters(it.id.toLong()) }
            }

        return serialize(
            OpdsDataClass(
                id = "opds",
                title = "Suwayomi OPDS Catalog",
                icon = "/favicon",
                updated = formattedNow,
                author = OpdsDataClass.Author("Suwayomi", "https://suwayomi.org/"),
                links =
                    listOf(
                        OpdsDataClass.Link(
                            rel = "self",
                            href = baseUrl,
                            type = "application/atom+xml;profile=opds-catalog;kind=navigation",
                        ),
                        OpdsDataClass.Link(
                            rel = "start",
                            href = baseUrl,
                            type = "application/atom+xml;profile=opds-catalog;kind=navigation",
                        ),
                    ),
                entries =
                    sources.map { source ->
                        OpdsDataClass.Entry(
                            updated = formattedNow,
                            id = source.id,
                            title = source.name,
                            link =
                                listOf(
                                    OpdsDataClass.Link(
                                        rel = "subsection",
                                        href = "$baseUrl/source/${source.id}",
                                        type = "application/atom+xml;profile=opds-catalog;kind=navigation",
                                    ),
                                ),
                        )
                    },
            ),
        )
    }

    fun getSourceFeed(
        sourceId: Long,
        baseUrl: String,
        pageNum: Int = 1,
    ): String {
        val formattedNow = opdsDateFormatter.format(Instant.now())
        val source = Source.getSource(sourceId)
        val (mangas, totalCount) =
            transaction {
                val query =
                    MangaTable
                        .join(ChapterTable, JoinType.INNER, onColumn = MangaTable.id, otherColumn = ChapterTable.manga)
                        .select(MangaTable.columns)
                        .where {
                            (MangaTable.sourceReference eq sourceId) and
                                (ChapterTable.isDownloaded eq true)
                        }.groupBy(MangaTable.id)
                        .orderBy(MangaTable.title to SortOrder.ASC)

                val totalCount = query.count()
                val paginatedResults =
                    query
                        .limit(ITEMS_PER_PAGE)
                        .offset(((pageNum - 1) * ITEMS_PER_PAGE).toLong())
                        .map { MangaTable.toDataClass(it) }

                Pair(paginatedResults, totalCount)
            }

        return serialize(
            OpdsDataClass(
                id = "source/$sourceId",
                title = source?.name ?: sourceId.toString(),
                updated = formattedNow,
                totalResults = totalCount,
                itemsPerPage = ITEMS_PER_PAGE,
                startIndex = (pageNum - 1) * ITEMS_PER_PAGE + 1,
                icon = source?.iconUrl,
                author =
                    OpdsDataClass.Author(
                        name = "Suwayomi",
                        uri = "https://suwayomi.org/",
                    ),
                links =
                    listOfNotNull(
                        OpdsDataClass.Link(
                            rel = "self",
                            href = "$baseUrl/source/$sourceId?pageNumber=$pageNum",
                            type = "application/atom+xml;profile=opds-catalog;kind=acquisition",
                        ),
                        OpdsDataClass.Link(
                            rel = "start",
                            href = baseUrl,
                            type = "application/atom+xml;profile=opds-catalog;kind=navigation",
                        ),
                    ),
                entries =
                    mangas.map { manga ->
                        OpdsDataClass.Entry(
                            id = "manga/${manga.id}",
                            title = manga.title,
                            updated = formattedNow,
                            authors = manga.author?.let { listOf(OpdsDataClass.Author(name = it)) } ?: emptyList(),
                            categories =
                                manga.genre.map { genre ->
                                    OpdsDataClass.Category(term = "", label = genre)
                                },
                            summary = manga.description?.let { OpdsDataClass.Summary(value = it) },
                            link =
                                listOfNotNull(
                                    OpdsDataClass.Link(
                                        rel = "subsection",
                                        href = "$baseUrl/manga/${manga.id}",
                                        type = "application/atom+xml;profile=opds-catalog;kind=acquisition",
                                    ),
                                    OpdsDataClass.Link(
                                        rel = "http://opds-spec.org/image",
                                        href = proxyThumbnailUrl(manga.id),
                                        type = "image/jpeg",
                                    ),
                                ),
                            content =
                                OpdsDataClass.Content(
                                    type = "text",
                                    value = manga.status,
                                ),
                        )
                    },
            ),
        )
    }

    suspend fun getMangaFeed(
        mangaId: Int,
        baseUrl: String,
        pageNum: Int = 1,
    ): String {
        val formattedNow = opdsDateFormatter.format(Instant.now())
        val manga = getManga(mangaId, false)
        val thumbnailUrl = proxyThumbnailUrl(manga.id)
        val (chapters, totalCount) = getPaginatedChapters(mangaId, pageNum)

        return serialize(
            OpdsDataClass(
                id = "manga/$mangaId",
                title = manga.title,
                updated = formattedNow,
                icon = thumbnailUrl,
                author =
                    OpdsDataClass.Author(
                        name = "Suwayomi",
                        uri = "https://suwayomi.org/",
                    ),
                totalResults = totalCount,
                itemsPerPage = ITEMS_PER_PAGE,
                startIndex = (pageNum - 1) * ITEMS_PER_PAGE + 1,
                links =
                    listOfNotNull(
                        OpdsDataClass.Link(
                            rel = "self",
                            href = "$baseUrl/manga/$mangaId?pageNumber=$pageNum",
                            type = "application/atom+xml;profile=opds-catalog;kind=acquisition",
                        ),
                        OpdsDataClass.Link(
                            rel = "start",
                            href = baseUrl,
                            type = "application/atom+xml;profile=opds-catalog;kind=navigation",
                        ),
                        OpdsDataClass.Link(
                            rel = "http://opds-spec.org/image",
                            href = thumbnailUrl,
                            type = "image/jpeg",
                        ),
                        OpdsDataClass.Link(
                            rel = "http://opds-spec.org/image/thumbnail",
                            href = thumbnailUrl,
                            type = "image/jpeg",
                        ),
//                        OpdsDataClass.Link(
//                            rel = "search",
//                            type = "application/opensearchdescription+xml",
//                            href = "$baseUrl/search"
//                        )
                    ),
                entries =
                    chapters.map { chapter ->
                        createChapterEntry(chapter, manga)
                    },
            ),
        )
    }

    private fun createChapterEntry(
        chapter: ChapterDataClass,
        manga: MangaDataClass,
    ): OpdsDataClass.Entry {
        val cbzFile = File(getChapterCbzPath(manga.id, chapter.id))
        val isCbzAvailable = cbzFile.exists()

        return OpdsDataClass.Entry(
            id = "chapter/${chapter.id}",
            title = chapter.name,
            updated = opdsDateFormatter.format(Instant.ofEpochMilli(chapter.uploadDate)),
            content = OpdsDataClass.Content(value = "${chapter.scanlator}"),
            summary = manga.description?.let { OpdsDataClass.Summary(value = it) },
            extent =
                cbzFile.takeIf { it.exists() }?.let {
                    formatFileSize(it.length())
                },
            format = cbzFile.takeIf { it.exists() }?.let { "CBZ" },
            authors =
                listOfNotNull(
                    manga.author?.let { OpdsDataClass.Author(name = it) },
                    manga.artist?.takeIf { it != manga.author }?.let { OpdsDataClass.Author(name = it) },
                ),
            link =
                listOfNotNull(
                    if (isCbzAvailable) {
                        OpdsDataClass.Link(
                            rel = "http://opds-spec.org/acquisition/open-access",
                            href = "/api/v1/manga/${manga.id}/chapter/${chapter.id}/download",
                            type = "application/vnd.comicbook+zip",
                        )
                    } else {
                        OpdsDataClass.Link(
                            rel = "http://vaemendis.net/opds-pse/stream",
                            href = "/api/v1/manga/${manga.id}/chapter/${chapter.index}/page/{pageNumber}",
                            type = "image/jpeg",
                            pseCount = chapter.pageCount,
                        )
                    },
                    OpdsDataClass.Link(
                        rel = "http://opds-spec.org/image",
                        href = "/api/v1/manga/${manga.id}/chapter/${chapter.index}/page/0",
                        type = "image/jpeg",
                    ),
                ),
        )
    }

    private fun getPaginatedChapters(
        mangaId: Int,
        pageNum: Int,
    ): Pair<List<ChapterDataClass>, Long> =
        transaction {
            val query =
                ChapterTable
                    .selectAll()
                    .where {
                        (ChapterTable.manga eq mangaId) and
                            (ChapterTable.isDownloaded eq true) and
                            (ChapterTable.pageCount greater 0)
                    }.orderBy(ChapterTable.sourceOrder to SortOrder.DESC)

            val totalCount = query.count()
            val results =
                query
                    .limit(ITEMS_PER_PAGE)
                    .offset(((pageNum - 1) * ITEMS_PER_PAGE).toLong())
                    .map {
                        ChapterTable.toDataClass(it)
                    }

            Pair(results, totalCount)
        }

    private val opdsDateFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    private fun formatFileSize(size: Long): String =
        when {
            size >= 1_000_000 -> "%.2f MB".format(size / 1_000_000.0)
            size >= 1_000 -> "%.2f KB".format(size / 1_000.0)
            else -> "$size bytes"
        }

    private fun sourceHasMangasWithChapters(sourceId: Long): Boolean =
        transaction {
            MangaTable
                .join(ChapterTable, JoinType.INNER, onColumn = MangaTable.id, otherColumn = ChapterTable.manga)
                .select(MangaTable.id)
                .where {
                    (MangaTable.sourceReference eq sourceId) and
                        (ChapterTable.isDownloaded eq true)
                }.limit(1)
                .any()
        }

    private val xmlFormat =
        XML {
            indent = 2
            xmlVersion = XmlVersion.XML10
            xmlDeclMode = XmlDeclMode.Charset
            defaultPolicy {
                autoPolymorphic = true
            }
        }

    private fun serialize(feed: OpdsDataClass): String = xmlFormat.encodeToString(OpdsDataClass.serializer(), feed)
}
