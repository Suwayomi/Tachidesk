/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package suwayomi.tachidesk.graphql.server

import com.expediagroup.graphql.generator.execution.FlowSubscriptionExecutionStrategy
import com.expediagroup.graphql.server.execution.GraphQLRequestHandler
import com.expediagroup.graphql.server.execution.GraphQLServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.GraphQL
import graphql.execution.AsyncExecutionStrategy
import io.javalin.http.Context
import io.javalin.websocket.WsCloseContext
import io.javalin.websocket.WsMessageContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import suwayomi.tachidesk.graphql.server.subscriptions.ApolloSubscriptionProtocolHandler

class TachideskGraphQLServer(
    requestParser: JavalinGraphQLRequestParser,
    contextFactory: TachideskGraphQLContextFactory,
    requestHandler: GraphQLRequestHandler,
) : GraphQLServer<Context>(requestParser, contextFactory, requestHandler) {
    private val objectMapper = jacksonObjectMapper()
    private val subscriptionProtocolHandler = ApolloSubscriptionProtocolHandler(contextFactory, requestHandler, objectMapper)

    @OptIn(DelicateCoroutinesApi::class)
    fun handleSubscriptionMessage(context: WsMessageContext) {
        subscriptionProtocolHandler
            .handleMessage(context)
            .map { objectMapper.writeValueAsString(it) }
            .map { context.send(it) }
            .launchIn(GlobalScope)
    }

    fun handleSubscriptionDisconnect(context: WsCloseContext) {
        subscriptionProtocolHandler.handleDisconnect(context)
    }

    companion object {
        private fun getGraphQLObject(): GraphQL =
            GraphQL
                .newGraphQL(schema)
                .subscriptionExecutionStrategy(FlowSubscriptionExecutionStrategy())
                .mutationExecutionStrategy(AsyncExecutionStrategy())
                .defaultDataFetcherExceptionHandler(TachideskDataFetcherExceptionHandler())
                .build()

        fun create(): TachideskGraphQLServer {
            val graphQL = getGraphQLObject()

            val requestParser = JavalinGraphQLRequestParser()
            val contextFactory = TachideskGraphQLContextFactory()
            val requestHandler = GraphQLRequestHandler(graphQL, TachideskDataLoaderRegistryFactory.create())

            return TachideskGraphQLServer(requestParser, contextFactory, requestHandler)
        }
    }
}
