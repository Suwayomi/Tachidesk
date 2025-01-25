package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.javalin.json.JsonMapper
import io.javalin.json.fromJsonString
import kotlinx.serialization.Serializable
import suwayomi.tachidesk.manga.impl.MangaList.processEntries
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.getCatalogueSourceOrStub
import suwayomi.tachidesk.manga.model.dataclass.PagedMangaListDataClass
import uy.kohesive.injekt.injectLazy

object Search {
    suspend fun sourceSearch(
        userId: Int,
        sourceId: Long,
        searchTerm: String,
        pageNum: Int,
    ): PagedMangaListDataClass {
        val source = getCatalogueSourceOrStub(sourceId)
        val searchManga = source.getSearchManga(pageNum, searchTerm, getFilterListOf(source))
        return searchManga.processEntries(userId, sourceId)
    }

    suspend fun sourceFilter(
        userId: Int,
        sourceId: Long,
        pageNum: Int,
        filter: FilterData,
    ): PagedMangaListDataClass {
        val source = getCatalogueSourceOrStub(sourceId)
        val filterList = if (filter.filter != null) buildFilterList(sourceId, filter.filter) else source.getFilterList()
        val searchManga = source.getSearchManga(pageNum, filter.searchTerm ?: "", filterList)
        return searchManga.processEntries(userId, sourceId)
    }

    private val filterListCache = mutableMapOf<Long, FilterList>()

    private fun getFilterListOf(
        source: CatalogueSource,
        reset: Boolean = false,
    ): FilterList {
        if (reset || !filterListCache.containsKey(source.id)) {
            filterListCache[source.id] = source.getFilterList()
        }
        return filterListCache[source.id]!!
    }

    fun getFilterList(
        sourceId: Long,
        reset: Boolean,
    ): List<FilterObject> {
        val source = getCatalogueSourceOrStub(sourceId)

        return getFilterListOf(source, reset).list.map {
            FilterObject(
                when (it) {
                    is Filter.Header -> "Header"
                    is Filter.Separator -> "Separator"
                    is Filter.Select<*> -> "Select"
                    is Filter.Text -> "Text"
                    is Filter.CheckBox -> "CheckBox"
                    is Filter.TriState -> "TriState"
                    is Filter.Group<*> -> "Group"
                    is Filter.Sort -> "Sort"
                    else -> throw RuntimeException("sealed class Cannot have more Subtypes!")
                },
                when (it) {
                    is Filter.Group<*> -> {
                        SerializableGroup(
                            it.name,
                            it.state.map { item ->
                                when (item) {
                                    is Filter.CheckBox -> FilterObject("CheckBox", item)
                                    is Filter.TriState -> FilterObject("TriState", item)
                                    is Filter.Text -> FilterObject("Text", item)
                                    is Filter.Select<*> -> FilterObject("Select", item)
                                    else -> throw RuntimeException("Illegal Group item type!")
                                }
                            },
                        )
                    }
                    else -> it
                },
            )
        }
    }

    private fun Filter.Select<*>.getValuesType(): String = values::class.java.componentType!!.simpleName

    class SerializableGroup(
        name: String,
        state: List<FilterObject>,
    ) : Filter<List<FilterObject>>(name, state)

    data class FilterObject(
        val type: String,
        val filter: Filter<*>,
    )

    fun setFilter(
        sourceId: Long,
        changes: List<FilterChange>,
    ) {
        val source = getCatalogueSourceOrStub(sourceId)
        val filterList = getFilterListOf(source, false)
        updateFilterList(filterList, changes)
    }

    private fun updateFilterList(
        filterList: FilterList,
        changes: List<FilterChange>,
    ): FilterList {
        changes.forEach { change ->
            when (val filter = filterList[change.position]) {
                is Filter.Header -> {
                    // NOOP
                }
                is Filter.Separator -> {
                    // NOOP
                }
                is Filter.Select<*> -> filter.state = change.state.toInt()
                is Filter.Text -> filter.state = change.state
                is Filter.CheckBox -> filter.state = change.state.toBooleanStrict()
                is Filter.TriState -> filter.state = change.state.toInt()
                is Filter.Group<*> -> {
                    val groupChange = jsonMapper.fromJsonString<FilterChange>(change.state)

                    when (val groupFilter = filter.state[groupChange.position]) {
                        is Filter.CheckBox -> groupFilter.state = groupChange.state.toBooleanStrict()
                        is Filter.TriState -> groupFilter.state = groupChange.state.toInt()
                        is Filter.Text -> groupFilter.state = groupChange.state
                        is Filter.Select<*> -> groupFilter.state = groupChange.state.toInt()
                    }
                }
                is Filter.Sort -> {
                    filter.state = jsonMapper.fromJsonString(change.state, Filter.Sort.Selection::class.java)
                }
            }
        }
        return filterList
    }

    fun buildFilterList(
        sourceId: Long,
        changes: List<FilterChange>,
    ): FilterList {
        val source = getCatalogueSourceOrStub(sourceId)
        val filterList = source.getFilterList()
        return updateFilterList(filterList, changes)
    }

    private val jsonMapper: JsonMapper by injectLazy()

    @Serializable
    data class FilterChange(
        val position: Int,
        val state: String,
    )

    @Serializable
    data class FilterData(
        val searchTerm: String?,
        val filter: List<FilterChange>?,
    )

    @Suppress("UNUSED_PARAMETER")
    fun sourceGlobalSearch(searchTerm: String) {
        // TODO
    }
}
