package nl.rogro82.pipup

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Push of state changes to a controller.
 *
 * A controller (the Home Assistant integration) sets a webhook URL through
 * `POST /settings?webhook=...`; from then on every state change is POSTed there as
 * the `/state` JSON plus `event` (and `reason` for a removal). That replaces a
 * controller polling `/state` on a timer. One thread, in order, fire and forget:
 * a failed POST is retried once after 2 s and then dropped. The controller reads
 * `/state` itself when it (re)connects, so a dropped push is not lost state.
 */
object Pusher {
    private const val LOG_TAG = "PiPupPush"
    private const val TIMEOUT_MS = 5000
    private const val RETRY_DELAY_MS = 2000L
    private const val MAX_PENDING = 16
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val HTTP_CLIENT_ERROR_MIN = 400
    private const val HTTP_CLIENT_ERROR_MAX = 499

    private val executor = Executors.newSingleThreadExecutor()

    private class Pending(val target: String, val event: String, val bodyProvider: () -> String?)
    private val pending = ArrayDeque<Pending>()
    private var draining = false
    private var overflowLogged = false

    /// Where pushes go; empty or null = push off. Set by the service from prefs.
    @Volatile var url: String? = null

    @Volatile var lastStatus: Int? = null
        private set
    @Volatile var lastAt: Long = 0L
        private set
    @Volatile var lastEvent: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /**
     * Enqueues a state-change webhook delivery for [event] with JSON [body].
     */
    fun push(event: String, body: String) = push(event) { body }

    /**
     * Enqueues a state-change webhook delivery for [event] whose JSON payload is built lazily
     * on the background delivery thread, keeping callers (such as the main thread) non-blocking.
     */
    fun push(event: String, bodyProvider: () -> String?) {
        val target = url?.takeIf { it.isNotBlank() } ?: return
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) {
                val dropped = pending.removeFirst()
                if (!overflowLogged) {
                    overflowLogged = true
                    Log.w(
                        LOG_TAG,
                        "push queue full ($MAX_PENDING): dropping oldest (${dropped.event}) and further overflow",
                    )
                }
            }
            pending.addLast(Pending(target, event, bodyProvider))
            if (draining) return
            draining = true
        }
        executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(pending) {
                pending.removeFirstOrNull().also {
                    if (it == null) { draining = false; overflowLogged = false }
                }
            } ?: return
            deliver(next)
        }
    }

    private fun deliver(p: Pending) {
        val body = p.bodyProvider() ?: return
        val code = post(p.target, p.event, body)
        val terminal = code != null &&
            (code in HTTP_OK_MIN..HTTP_OK_MAX || code in HTTP_CLIENT_ERROR_MIN..HTTP_CLIENT_ERROR_MAX)
        if (terminal || synchronized(pending) { pending.isNotEmpty() }) return
        try {
            Thread.sleep(RETRY_DELAY_MS)
            post(p.target, p.event, body)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun post(target: String, event: String, body: String): Int? {
        return try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "PiPup/${BuildConfig.VERSION_NAME}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            runCatching { conn.inputStream.close() }
            conn.disconnect()
            lastStatus = code
            lastAt = System.currentTimeMillis()
            lastEvent = event
            lastError = if (code in HTTP_OK_MIN..HTTP_OK_MAX) null else "HTTP $code"
            code
        } catch (ex: java.io.IOException) {
            recordPostFailure(event, ex)
            null
        } catch (ex: IllegalArgumentException) {
            recordPostFailure(event, ex)
            null
        } catch (ex: SecurityException) {
            recordPostFailure(event, ex)
            null
        }
    }

    private fun recordPostFailure(event: String, ex: Exception) {
        lastStatus = null
        lastAt = System.currentTimeMillis()
        lastEvent = event
        lastError = ex.message ?: ex.javaClass.simpleName
        Log.w(LOG_TAG, "push $event failed: $lastError")
    }
}
