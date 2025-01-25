package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.javalin.json.JavalinJackson
import io.javalin.json.toJsonString
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import rx.Observable
import suwayomi.tachidesk.manga.impl.Search.FilterChange
import suwayomi.tachidesk.manga.impl.Search.FilterObject
import suwayomi.tachidesk.manga.impl.Search.SerializableGroup
import suwayomi.tachidesk.manga.impl.Search.getFilterList
import suwayomi.tachidesk.manga.impl.Search.setFilter
import suwayomi.tachidesk.manga.impl.Search.sourceSearch
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.registerCatalogueSource
import suwayomi.tachidesk.manga.impl.util.source.GetCatalogueSource.unregisterCatalogueSource
import suwayomi.tachidesk.manga.impl.util.source.StubSource
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.createSMangas
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchTest : ApplicationTest() {
    class FakeSearchableSource(
        id: Long,
    ) : StubSource(id) {
        var mangas: List<SManga> = emptyList()

        @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
        override fun fetchSearchManga(
            page: Int,
            query: String,
            filters: FilterList,
        ): Observable<MangasPage> = Observable.just(MangasPage(mangas, false))
    }

    private val sourceId = 1L
    private val source = FakeSearchableSource(sourceId)
    private val mangasCount = 10

    @BeforeAll
    fun setup() {
        registerCatalogueSource(sourceId to source)

        this.source.mangas = createSMangas(mangasCount)
    }

    @Test
    fun searchWorks() {
        val searchResults =
            runBlocking {
                // todo user accounts
                sourceSearch(1, sourceId, "all the mangas", 1)
            }

        assertEquals(mangasCount, searchResults.mangaList.size, "should return all the mangas")
    }

    @AfterAll
    fun teardown() {
        unregisterCatalogueSource(this.sourceId)
    }
}

@Suppress("UNCHECKED_CAST")
class FilterListTest : ApplicationTest() {
    open class EmptyFilterListSource(
        id: Long,
    ) : StubSource(id) {
        open var mFilterList = FilterList()

        override fun getFilterList(): FilterList = mFilterList
    }

    @Test
    fun `empty FilterList returns empty List`() {
        val source = registerSource(EmptyFilterListSource::class)
        source.mFilterList = FilterList()

        val filterList = getFilterList(source.id, false)

        assertEquals(
            0,
            filterList.size,
        )
    }

    class FilterListSource(
        id: Long,
    ) : EmptyFilterListSource(id) {
        class SelectFilter(
            name: String,
            values: Array<String>,
        ) : Filter.Select<String>(name, values)

        class TextFilter(
            name: String,
        ) : Filter.Text(name)

        class TestCheckBox(
            name: String,
        ) : Filter.CheckBox(name, false)

        class TriState(
            name: String,
            state: Int,
        ) : Filter.TriState(name, state)

        class Group(
            name: String,
            state: List<TestCheckBox>,
        ) : Filter.Group<TestCheckBox>(name, state)

        class Sort(
            name: String,
            values: Array<String>,
            state: Selection,
        ) : Filter.Sort(name, values, state)

        override var mFilterList =
            FilterList(
                Filter.Header("This is a header"),
                Filter.Separator(),
                SelectFilter("Select one of these:", arrayOf("this", "that", "none of them")),
                TextFilter("text filter"),
                TestCheckBox("check this or else!"),
                TriState("wanna hook up?", Filter.TriState.STATE_IGNORE),
                Group(
                    "my Todo",
                    listOf(
                        TestCheckBox("Write Tests"),
                        TestCheckBox("Write More Tests"),
                        TestCheckBox("Write Even More Tests"),
                    ),
                ),
                Sort(
                    "Sort",
                    arrayOf("Alphabetic", "Date published", "Rating"),
                    Filter.Sort.Selection(2, false),
                ),
            )
    }

    @Test
    fun convertsEveryTypeCorrectly() {
        val source = registerSource(FilterListSource::class)
        val filterList = getFilterList(source.id, false)

        assertEquals(
            FilterObject("Header", source.mFilterList[0]),
            filterList[0],
        )
        assertEquals(
            FilterObject("Separator", source.mFilterList[1]),
            filterList[1],
        )
        assertEquals(
            FilterObject("Select", source.mFilterList[2]),
            filterList[2],
        )
        assertEquals(
            FilterObject("Text", source.mFilterList[3]),
            filterList[3],
        )
        assertEquals(
            FilterObject("CheckBox", source.mFilterList[4]),
            filterList[4],
        )
        assertEquals(
            FilterObject("TriState", source.mFilterList[5]),
            filterList[5],
        )
        assertEquals(
            filterList[6],
            FilterObject(
                "Group",
                SerializableGroup(
                    source.mFilterList[6].name,
                    listOf(
                        FilterObject("CheckBox", (source.mFilterList[6].state as List<Filter<*>>)[0]),
                        FilterObject("CheckBox", (source.mFilterList[6].state as List<Filter<*>>)[1]),
                        FilterObject("CheckBox", (source.mFilterList[6].state as List<Filter<*>>)[2]),
                    ),
                ),
            ),
        )
        assertEquals(
            FilterObject("Sort", source.mFilterList[7]),
            filterList[7],
        )

        // make sure that we can convert this to json
        JavalinJackson().toJsonString(filterList)
    }

    fun setFilter(
        sourceId: Long,
        filterChange: FilterChange,
    ) {
        setFilter(sourceId, listOf(filterChange))
    }

    @Test
    fun `Header and Separator should not change`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(0, "change!")),
        )

        setFilter(
            source.id,
            listOf(FilterChange(1, "change!")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            filterList[0].filter.state,
            0,
        )

        assertEquals(
            filterList[1].filter.state,
            0,
        )
    }

    @Test
    fun `Select changes are Int`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(2, "1")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            filterList[2].filter.state,
            1,
        )
    }

    @Test
    fun `Text changes are String`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(3, "I'm a changed man!")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            filterList[3].filter.state,
            "I'm a changed man!",
        )
    }

    @Test
    fun `CheckBox changes are Boolean`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(4, "true")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            filterList[4].filter.state,
            true,
        )
    }

    @Test
    fun `TriState changes are Int`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(5, "1")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            filterList[5].filter.state,
            Filter.TriState.STATE_INCLUDE,
        )
    }

    @Test
    fun `Group changes are Filters`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(6, """{"position":0,"state":"true"}""")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            (filterList[6].filter.state as List<FilterObject>)[0].filter.state,
            true,
        )
    }

    @Test
    fun `Sort changes are Filter,Sort,Selection`() {
        val source = registerSource(FilterListSource::class)

        setFilter(
            source.id,
            listOf(FilterChange(7, """{"index":1,"ascending":"true"}""")),
        )

        val filterList = getFilterList(source.id, false)

        assertEquals(
            filterList[7].filter.state,
            Filter.Sort.Selection(1, true),
        )
    }

    companion object {
        private var sourceCount = 0L

        private fun registerSource(sourceClass: KClass<*>): EmptyFilterListSource =
            synchronized(sourceClass) {
                val source = sourceClass.primaryConstructor!!.call(sourceCount) as EmptyFilterListSource
                registerCatalogueSource(sourceCount to source)
                sourceCount++
                source
            }

        @AfterAll
        @JvmStatic
        fun teardown() {
            (0 until sourceCount).forEach { unregisterCatalogueSource(it) }
        }
    }
}
