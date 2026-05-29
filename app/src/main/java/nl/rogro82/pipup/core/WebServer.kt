package nl.rogro82.pipup.core

import fi.iki.elonen.NanoHTTPD

/**
 * Enhanced NanoHTTPD WebServer with routing and callback.
 */
class WebServer(port: Int, private val handler: Handler) : NanoHTTPD(port) {

    interface Handler {
        fun handleRequest(session: IHTTPSession): Response
    }

    override fun serve(session: IHTTPSession?): Response {
        return if (session != null) {
            handler.handleRequest(session)
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Null session")
        }
    }
}
