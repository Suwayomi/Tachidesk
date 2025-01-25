package suwayomi.tachidesk.manga.impl.track.tracker

import suwayomi.tachidesk.manga.impl.track.tracker.anilist.Anilist
import suwayomi.tachidesk.manga.impl.track.tracker.mangaupdates.MangaUpdates
import suwayomi.tachidesk.manga.impl.track.tracker.myanimelist.MyAnimeList

object TrackerManager {
    const val MYANIMELIST = 1
    const val ANILIST = 2
    const val KITSU = 3
    const val SHIKIMORI = 4
    const val BANGUMI = 5
    const val KOMGA = 6
    const val MANGA_UPDATES = 7
    const val KAVITA = 8
    const val SUWAYOMI = 9

    val myAnimeList = MyAnimeList(MYANIMELIST)
    val aniList = Anilist(ANILIST)

//    val kitsu = Kitsu(KITSU)
//    val shikimori = Shikimori(SHIKIMORI)
//    val bangumi = Bangumi(BANGUMI)
//    val komga = Komga(KOMGA)
    val mangaUpdates = MangaUpdates(MANGA_UPDATES)
//    val kavita = Kavita(context, KAVITA)
//    val suwayomi = Suwayomi(SUWAYOMI)

    val services: List<Tracker> = listOf(myAnimeList, aniList, mangaUpdates)

    fun getTracker(id: Int) = services.find { it.id == id }

    fun hasLoggedTracker(userId: Int) = services.any { it.isLoggedIn(userId) }
}
