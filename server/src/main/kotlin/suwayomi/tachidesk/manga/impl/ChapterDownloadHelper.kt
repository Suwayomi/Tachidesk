package suwayomi.tachidesk.manga.impl

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import suwayomi.tachidesk.manga.impl.Manga.getManga
import suwayomi.tachidesk.manga.impl.download.fileProvider.ChaptersFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.ArchiveProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.impl.FolderProvider
import suwayomi.tachidesk.manga.impl.download.model.DownloadChapter
import suwayomi.tachidesk.manga.impl.util.getChapterCbzPath
import suwayomi.tachidesk.manga.impl.util.getChapterDownloadPath
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.serverConfig
import java.io.File
import java.io.IOException
import java.io.InputStream

object ChapterDownloadHelper {
    fun getImage(
        mangaId: Int,
        chapterId: Int,
        index: Int,
    ): Pair<InputStream, String> = provider(mangaId, chapterId).getImage().execute(index)

    fun delete(
        mangaId: Int,
        chapterId: Int,
    ): Boolean = provider(mangaId, chapterId).delete()

    suspend fun download(
        mangaId: Int,
        chapterId: Int,
        download: DownloadChapter,
        scope: CoroutineScope,
        step: suspend (DownloadChapter?, Boolean) -> Unit,
    ): Boolean = provider(mangaId, chapterId).download().execute(download, scope, step)

    // return the appropriate provider based on how the download was saved. For the logic is simple but will evolve when new types of downloads are available
    private fun provider(
        mangaId: Int,
        chapterId: Int,
    ): ChaptersFilesProvider<*> {
        val chapterFolder = File(getChapterDownloadPath(mangaId, chapterId))
        val cbzFile = File(getChapterCbzPath(mangaId, chapterId))
        if (cbzFile.exists()) return ArchiveProvider(mangaId, chapterId)
        if (!chapterFolder.exists() && serverConfig.downloadAsCbz.value) return ArchiveProvider(mangaId, chapterId)
        return FolderProvider(mangaId, chapterId)
    }

    suspend fun getCbzDownload(
        mangaId: Int,
        chapterId: Int,
    ): Triple<InputStream, String, String> {
        val chapter =
            transaction {
                ChapterTable
                    .selectAll()
                    .where { (ChapterTable.id eq chapterId) and (ChapterTable.manga eq mangaId) }
                    .firstOrNull()
                    ?.let { ChapterTable.toDataClass(it) }
            } ?: throw Exception("Chapter not found")

        val provider = provider(mangaId, chapter.id)

        return if (provider is ArchiveProvider) {
            val cbzFile = File(getChapterCbzPath(mangaId, chapter.id))
            val fileName = "${getManga(mangaId, false).title} - [${chapter.scanlator}] ${chapter.name}.cbz"
            Triple(cbzFile.inputStream(), "application/vnd.comicbook+zip", fileName)
        } else {
            throw IOException("Chapter not available as CBZ")
        }
    }
}
