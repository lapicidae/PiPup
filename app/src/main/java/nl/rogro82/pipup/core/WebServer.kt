package nl.rogro82.pipup.core

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.Executors

/**
 * Enhanced NanoHTTPD WebServer with routing, callback and thread pooling.
 */
class WebServer(port: Int, private val handler: Handler) : NanoHTTPD(port) {

    private val poolRunner = PooledAsyncRunner(16)

    init {
        setAsyncRunner(poolRunner)
    }

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

    override fun stop() {
        super.stop()
        poolRunner.shutdown()
    }

    /**
     * Custom AsyncRunner that uses a fixed thread pool to prevent CPU thrashing
     * and socket drops during high-frequency parallel requests.
     */
    private class PooledAsyncRunner(threadCount: Int) : AsyncRunner {
        private val executor = Executors.newFixedThreadPool(threadCount)

        override fun exec(clientHandler: ClientHandler) {
            executor.submit(clientHandler)
        }

        fun shutdown() {
            executor.shutdown()
        }

        override fun closeAll() {
            // Not used in this implementation, shutdown handles it
        }

        override fun closed(clientHandler: ClientHandler?) {
            // Not used
        }
    }
}
