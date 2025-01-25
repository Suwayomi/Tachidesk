package suwayomi.tachidesk.graphql.server

import com.expediagroup.graphql.server.extensions.getFromContext
import graphql.ExceptionWhileDataFetching
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.UnauthorizedException
import java.util.concurrent.CompletableFuture

class TachideskDataFetcherExceptionHandler : SimpleDataFetcherExceptionHandler() {
    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters,
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val exception = handlerParameters.exception
        if (exception is UnauthorizedException) {
            val error = ExceptionWhileDataFetching(handlerParameters.path, exception, handlerParameters.sourceLocation)
            logException(error, exception)
            // Set the HTTP status code to 401
            handlerParameters.dataFetchingEnvironment.getFromContext<Context>()?.status(HttpStatus.UNAUTHORIZED)
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
        }
        if (exception is ForbiddenException) {
            val error = ExceptionWhileDataFetching(handlerParameters.path, exception, handlerParameters.sourceLocation)
            logException(error, exception)
            // Set the HTTP status code to 403
            handlerParameters.dataFetchingEnvironment.getFromContext<Context>()?.status(HttpStatus.FORBIDDEN)
            return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build())
        }
        return super.handleException(handlerParameters)
    }
}
