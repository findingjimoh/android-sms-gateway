package me.capcom.smsgateway.modules.localserver.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import me.capcom.smsgateway.modules.receiver.ConversationsService

class ConversationsRoutes(
    private val context: Context,
    private val conversationsService: ConversationsService,
) {

    fun register(routing: Route) {
        routing.apply {
            conversationsRoutes()
        }
    }

    private fun Route.conversationsRoutes() {
        get {
            try {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val conversations = conversationsService.getConversations(context, limit, offset)
                call.respond(conversations)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Failed to retrieve conversations: ${e.message}")
                )
            }
        }

        get("{phone}") {
            try {
                val phone = call.parameters["phone"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Phone number is required"))

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val messages = conversationsService.getThread(context, phone, limit, offset)
                call.respond(messages)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Failed to retrieve thread: ${e.message}")
                )
            }
        }
    }

    fun registerReceivedRoute(routing: Route) {
        routing.apply {
            receivedRoute()
        }
    }

    private fun Route.receivedRoute() {
        get {
            try {
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "Parameter 'since' is required (unix timestamp in millis)")
                    )

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val messages = conversationsService.getRecent(context, since, limit)
                call.respond(messages)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Failed to retrieve received messages: ${e.message}")
                )
            }
        }
    }
}
