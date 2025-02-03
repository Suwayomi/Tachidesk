package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.http.UnauthorizedResponse
import io.javalin.http.staticfiles.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.eclipse.jetty.server.ServerConnector
import suwayomi.tachidesk.global.GlobalAPI
import suwayomi.tachidesk.graphql.GraphQL
import suwayomi.tachidesk.manga.MangaAPI
import suwayomi.tachidesk.opds.OpdsAPI
import suwayomi.tachidesk.server.util.Browser
import suwayomi.tachidesk.server.util.WebInterfaceManager
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

object JavalinSetup {
    private val logger = KotlinLogging.logger {}

    private val applicationDirs: ApplicationDirs by injectLazy()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun <T> future(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = scope.future(block = block)

    fun javalinSetup() {
        val app =
            Javalin.create { config ->
                if (serverConfig.webUIEnabled.value) {
                    val serveWebUI = {
                        config.spaRoot.addFile("/", applicationDirs.webUIRoot + "/index.html", Location.EXTERNAL)
                    }
                    WebInterfaceManager.setServeWebUI(serveWebUI)

                    runBlocking {
                        WebInterfaceManager.setupWebUI()
                    }

                    logger.info { "Serving web static files for ${serverConfig.webUIFlavor.value}" }
                    config.staticFiles.add(applicationDirs.webUIRoot, Location.EXTERNAL)
                    serveWebUI()

                    // config.registerPlugin(OpenApiPlugin(getOpenApiOptions()))
                }

                var connectorAdded = false
                config.jetty.modifyServer { server ->
                    if (!connectorAdded) {
                        val connector =
                            ServerConnector(server).apply {
                                host = serverConfig.ip.value
                                port = serverConfig.port.value
                            }
                        server.addConnector(connector)

                        serverConfig.subscribeTo(
                            combine(
                                serverConfig.ip,
                                serverConfig.port,
                            ) { ip, port -> Pair(ip, port) },
                            { (newIp, newPort) ->
                                val oldIp = connector.host
                                val oldPort = connector.port

                                connector.host = newIp
                                connector.port = newPort
                                connector.stop()
                                connector.start()

                                logger.info { "Server ip and/or port changed from $oldIp:$oldPort to $newIp:$newPort " }
                            },
                        )
                        connectorAdded = true
                    }
                }

                config.bundledPlugins.enableCors { cors ->
                    cors.addRule {
                        it.allowCredentials = true
                        it.reflectClientOrigin = true
                    }
                }

                config.router.apiBuilder {
                    path("api/") {
                        path("v1/") {
                            GlobalAPI.defineEndpoints()
                            MangaAPI.defineEndpoints()
                        }

                        OpdsAPI.defineEndpoints()
                        GraphQL.defineEndpoints()
                    }
                }
            }

        app.beforeMatched { ctx ->
            fun credentialsValid(): Boolean {
                val basicAuthCredentials = ctx.basicAuthCredentials() ?: return false
                val (username, password) = basicAuthCredentials
                return username == serverConfig.basicAuthUsername.value &&
                    password == serverConfig.basicAuthPassword.value
            }

            if (serverConfig.basicAuthEnabled.value && !credentialsValid()) {
                ctx.header("WWW-Authenticate", "Basic")
                throw UnauthorizedResponse()
            }
        }

        app.events { event ->
            event.serverStarted {
                if (serverConfig.initialOpenInBrowserEnabled.value) {
                    Browser.openInBrowser()
                }
            }
        }

        // when JVM is prompted to shutdown, stop javalin gracefully
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                app.stop()
            },
        )

        app.exception(NullPointerException::class.java) { e, ctx ->
            logger.error(e) { "NullPointerException while handling the request" }
            ctx.status(404)
        }
        app.exception(NoSuchElementException::class.java) { e, ctx ->
            logger.error(e) { "NoSuchElementException while handling the request" }
            ctx.status(404)
        }
        app.exception(IOException::class.java) { e, ctx ->
            logger.error(e) { "IOException while handling the request" }
            ctx.status(500)
            ctx.result(e.message ?: "Internal Server Error")
        }

        app.exception(IllegalArgumentException::class.java) { e, ctx ->
            logger.error(e) { "IllegalArgumentException while handling the request" }
            ctx.status(400)
            ctx.result(e.message ?: "Bad Request")
        }

        app.start()
    }

    // private fun getOpenApiOptions(): OpenApiOptions {
    //     val applicationInfo =
    //         Info().apply {
    //             version("1.0")
    //             description("Suwayomi-Server Api")
    //         }
    //     return OpenApiOptions(applicationInfo).apply {
    //         path("/api/openapi.json")
    //         swagger(
    //             SwaggerOptions("/api/swagger-ui").apply {
    //                 title("Suwayomi-Server Swagger Documentation")
    //             },
    //         )
    //     }
    // }
}
