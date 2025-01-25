/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.server

import com.expediagroup.graphql.server.execution.GraphQLContextFactory
import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import io.javalin.http.Context
import io.javalin.websocket.WsContext
import org.dataloader.BatchLoaderEnvironment
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute

/**
 * Custom logic for how Suwayomi-Server should create its context given the [Context]
 */
class TachideskGraphQLContextFactory : GraphQLContextFactory<Context> {
    override suspend fun generateContext(request: Context): GraphQLContext =
        mapOf(
            Context::class to request,
            request.getPair(Attribute.TachideskUser),
        ).toGraphQLContext()

    fun generateContextMap(request: WsContext): Map<*, Any> =
        mapOf(
            Context::class to request,
            request.getPair(Attribute.TachideskUser),
        )

    private fun <T : Any> Context.getPair(attribute: Attribute<T>) = attribute to getAttribute(attribute)

    private fun <T : Any> WsContext.getPair(attribute: Attribute<T>) = attribute to getAttribute(attribute)
}

/**
 * Create a [GraphQLContext] from [this] map
 * @return a new [GraphQLContext]
 */
fun Map<*, Any?>.toGraphQLContext(): GraphQLContext = GraphQLContext.of(this)

fun <T : Any> GraphQLContext.getAttribute(attribute: Attribute<T>): T = get(attribute)

fun <T : Any> DataFetchingEnvironment.getAttribute(attribute: Attribute<T>): T = graphQlContext.get(attribute)

val BatchLoaderEnvironment.graphQlContext: GraphQLContext
    get() = keyContextsList.filterIsInstance<GraphQLContext>().first()

fun <T : Any> BatchLoaderEnvironment.getAttribute(attribute: Attribute<T>): T = graphQlContext.getAttribute(attribute)
