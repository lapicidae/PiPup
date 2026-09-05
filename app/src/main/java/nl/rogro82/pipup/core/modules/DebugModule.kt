package nl.rogro82.pipup.core.modules

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.Json
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.PiPupModule

/**
 * Module providing debug and diagnostic endpoints.
 * Only intended for use in debug builds.
 */
class DebugModule : PiPupModule {

    companion object {
        private const val TAG = "DebugModule"
    }

    override val id: String = "debug"
    override val name: String = "Debug & Diagnostics"
    override val supportedRoutes: List<String> = listOf("/debug/idle", "/debug/unload", "/debug/memory")

    private var moduleContext: ModuleContext? = null

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Debug module enabled")
        this.moduleContext = context
    }

    override fun onDisable() {
        Log.d(TAG, "Debug module disabled")
        moduleContext = null
    }

    override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val method = session.method
        val uri = session.uri.lowercase()

        return when (uri) {
            "/debug/idle" -> if (method == NanoHTTPD.Method.POST) handleSetIdle(session) else null
            "/debug/unload" -> if (method == NanoHTTPD.Method.POST) handleUnload() else null
            "/debug/memory" -> if (method == NanoHTTPD.Method.GET) handleMemoryStats() else null
            else -> null
        }
    }

    private fun handleSetIdle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val ms = session.parameters["ms"]?.firstOrNull()?.toLongOrNull()
            ?: return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "Missing or invalid 'ms' parameter")

        val app = moduleContext?.androidContext?.applicationContext as? PiPupApp
        app?.moduleManager?.idleTimeoutMs = ms

        Log.i(TAG, "Debug: Updated idle timeout to $ms ms")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "Idle timeout updated to $ms ms")
    }

    private fun handleUnload(): NanoHTTPD.Response {
        val app = moduleContext?.androidContext?.applicationContext as? PiPupApp
        app?.moduleManager?.forceIdleCleanup()

        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "Forced idle cleanup triggered")
    }

    private fun handleMemoryStats(): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse("Missing context")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val usedJavaHeap = (runtime.totalMemory() - runtime.freeMemory()) / 1024

        val stats = mutableMapOf<String, Any>(
            "java_heap_used_kb" to usedJavaHeap,
            "java_heap_total_kb" to runtime.totalMemory() / 1024,
            "native_heap_used_kb" to Debug.getNativeHeapAllocatedSize() / 1024,
            "system_avail_mem_mb" to memInfo.availMem / (1024 * 1024),
            "system_low_mem_warning" to memInfo.lowMemory
        )

        // Add module states
        val app = context.applicationContext as? PiPupApp
        app?.moduleManager?.let { mm ->
            val mediaModule = mm.getModule("media") as? MediaModule
            stats["media_module_prewarmed"] = mediaModule?.warmWebView != null
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            Json.writeValueAsString(stats)
        )
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        state["debug_enabled"] = true
    }
}
