package nl.rogro82.pipup.core.modules

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.text.Normalizer
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.DiscoveredPeer
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.PeerDiscoveryProvider
import nl.rogro82.pipup.core.PiPupModule

/**
 * Module responsible for Network Service Discovery (NSD).
 * Handles both registering this device and discovering other PiPup instances.
 */
class DiscoveryModule : PiPupModule, PeerDiscoveryProvider {

    /** Logging and NSD service type constants for [DiscoveryModule]. */
    companion object {
        private const val TAG = "DiscoveryModule"
        private const val SERVICE_TYPE = "_pipup._tcp."
        private const val RESOLVE_QUEUE_DELAY_MS = 300L
        private const val RESOLVE_TIMEOUT_MS = 5000L
    }

    override val id: String = "discovery"
    override val nameRes: Int = R.string.settings_module_discovery
    override val descriptionRes: Int = R.string.settings_module_discovery_desc

    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.OFF, ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.ON
    override val isUserConfigurable: Boolean = true

    private var moduleContext: ModuleContext? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredDevices = mutableMapOf<String, NsdServiceInfo>()

    private var peerListener: PeerDiscoveryProvider.Listener? = null
    private var mLocalServiceName: String? = null

    // Queue for resolving services to avoid NsdManager bottlenecks
    private data class ResolveRequest(val service: NsdServiceInfo, var retries: Int = 0)
    private val resolveQueue = java.util.ArrayDeque<ResolveRequest>()
    private var isResolving = false
    private val queueHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentRequest: ResolveRequest? = null
    private var activeInfoCallback: Any? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val resolveTimeoutRunnable = Runnable {
        val timedOut = currentRequest
        if (timedOut != null) {
            Log.w(TAG, "Resolve timed out for ${timedOut.service.serviceName} (Try ${timedOut.retries + 1}/3)")
            unregisterActiveInfoCallback()
            if (timedOut.retries < 2) {
                timedOut.retries++
                synchronized(resolveQueue) { resolveQueue.add(timedOut) }
            }
            finishResolve(timedOut)
        }
    }

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Discovery module enabled, registering service")
        this.moduleContext = context
        this.nsdManager = context.getSystemService(android.content.Context.NSD_SERVICE) as NsdManager
        registerService()
    }

    override fun onDisable() {
        Log.d(TAG, "Discovery module disabled, unregistering service")
        unregisterService()
        stopDiscovery()
        multicastLock = null
        moduleContext = null
        nsdManager = null
    }

    private fun registerService() {
        val context = moduleContext ?: return
        val nsd = nsdManager ?: return
        try {
            val sanitizedName = sanitizeDeviceName(getDeviceName())
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "PiPup $sanitizedName".take(63)
                serviceType = SERVICE_TYPE
                port = 7979
                // Attributes for easier identification
                setAttribute("id", context.settings.deviceId)
                setAttribute("name", sanitizedName)
                setAttribute("version", BuildConfig.VERSION_NAME)
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service registered: ${info.serviceName}")
                    mLocalServiceName = info.serviceName
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD Service unregistered")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD Unregistration failed: $errorCode")
                }
            }

            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Failed to register NSD service", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to register NSD service", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Failed to unregister NSD service", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to unregister NSD service", e)
            }
        }
        registrationListener = null
    }

    override fun startPeerDiscovery(listener: PeerDiscoveryProvider.Listener) {
        val nsd = nsdManager ?: return
        if (discoveryListener != null) stopDiscovery()

        acquireMulticastLock()
        this.peerListener = listener
        discoveredDevices.clear()

        synchronized(resolveQueue) {
            resolveQueue.clear()
            currentRequest = null
            isResolving = false
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                // Use contains because types might have trailing dots
                if (service.serviceType.contains(SERVICE_TYPE)) {
                    synchronized(resolveQueue) {
                        val alreadyQueued = resolveQueue.any { it.service.serviceName == service.serviceName }
                        val currentlyResolving = currentRequest?.service?.serviceName == service.serviceName
                        if (!alreadyQueued && !currentlyResolving) {
                            resolveQueue.add(ResolveRequest(service))
                            processNextInQueue()
                        }
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                discoveredDevices.remove(service.serviceName)
                synchronized(resolveQueue) {
                    resolveQueue.removeAll { it.service.serviceName == service.serviceName }
                    val active = currentRequest
                    if (active?.service?.serviceName == service.serviceName) {
                        unregisterActiveInfoCallback()
                        finishResolve(active)
                    }
                }
                peerListener?.onPeerLost(service.serviceName)
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "Discovery stopped: $regType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
                nsd.stopServiceDiscovery(this)
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /**
     * Processes the next service in the resolution queue to avoid NsdManager bottlenecks.
     */
    private fun processNextInQueue() {
        synchronized(resolveQueue) {
            if (isResolving || resolveQueue.isEmpty() || discoveryListener == null) return

            val nextRequest = resolveQueue.poll() ?: return
            currentRequest = nextRequest
            isResolving = true

            Log.d(
                TAG,
                "Next in queue: ${nextRequest.service.serviceName} (Try ${nextRequest.retries + 1}), waiting 300ms...",
            )

            queueHandler.postDelayed({
                resolveServiceInternal(nextRequest)
            }, RESOLVE_QUEUE_DELAY_MS)
        }
    }

    private fun resolveServiceInternal(request: ResolveRequest) {
        if (currentRequest !== request || discoveryListener == null) return
        val nsd = nsdManager
        val context = moduleContext?.androidContext
        if (nsd == null || context == null) return
        val service = request.service
        Log.d(TAG, "Starting resolution for: ${service.serviceName}")

        // Start timeout watchdog (5s for slow emulators)
        queueHandler.removeCallbacks(resolveTimeoutRunnable)
        queueHandler.postDelayed(resolveTimeoutRunnable, RESOLVE_TIMEOUT_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterActiveInfoCallback()
            fun detach(cb: NsdManager.ServiceInfoCallback, unregister: Boolean = true) {
                if (unregister) {
                    try {
                        nsd.unregisterServiceInfoCallback(cb)
                    } catch (_: Exception) {}
                }
                if (activeInfoCallback === cb) activeInfoCallback = null
            }

            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (currentRequest !== request || discoveryListener == null) {
                        detach(this)
                        return
                    }
                    if (serviceInfo.getHostAddress() == null) {
                        return
                    }
                    detach(this)
                    onResolveFinished(request, serviceInfo)
                }

                override fun onServiceLost() {
                    detach(this)
                    if (currentRequest === request) {
                        finishResolve(request)
                    }
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.e(TAG, "Callback registration failed: $errorCode")
                    detach(this, unregister = false)
                    finishResolve(request)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    // Unregistration completes asynchronously; state is already cleared in detach().
                }
            }
            activeInfoCallback = callback
            try {
                nsd.registerServiceInfoCallback(service, ContextCompat.getMainExecutor(context), callback)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Failed to register service info callback", e)
                detach(callback, unregister = false)
                finishResolve(request)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to register service info callback", e)
                detach(callback, unregister = false)
                finishResolve(request)
            }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode")
                    finishResolve(request)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    onResolveFinished(request, serviceInfo)
                }
            })
        }
    }

    private fun onResolveFinished(request: ResolveRequest, serviceInfo: NsdServiceInfo) {
        if (currentRequest !== request || discoveryListener == null) return
        val hostAddress = serviceInfo.getHostAddress()
        Log.d(TAG, "Service resolved successfully: ${serviceInfo.serviceName} -> $hostAddress")
        discoveredDevices[serviceInfo.serviceName] = serviceInfo

        if (hostAddress != null) {
            val remoteId = serviceInfo.attributes["id"]?.let { String(it) }
            val peer = DiscoveredPeer(
                name = serviceInfo.serviceName,
                address = hostAddress,
                id = remoteId
            )
            peerListener?.onPeerFound(peer)
        }

        finishResolve(request)
    }

    private fun finishResolve(request: ResolveRequest) {
        synchronized(resolveQueue) {
            if (currentRequest !== request) return
            queueHandler.removeCallbacks(resolveTimeoutRunnable)
            currentRequest = null
            isResolving = false
            processNextInQueue()
        }
    }

    private fun unregisterActiveInfoCallback() {
        val cb = activeInfoCallback ?: return
        activeInfoCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                nsdManager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback)
            } catch (_: Exception) {}
        }
    }

    private fun acquireMulticastLock() {
        val ctx = moduleContext?.androidContext ?: return
        try {
            val wifiManager = ctx.applicationContext
                .getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
            if (multicastLock == null && wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("PiPupNsdDiscovery").apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not release MulticastLock: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not release MulticastLock: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "Could not release MulticastLock: ${e.message}")
        }
    }

    private fun NsdServiceInfo.getHostAddress(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (hostAddresses.firstOrNull { it is Inet4Address } ?: hostAddresses.firstOrNull())?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            host?.hostAddress
        }
    }

    override fun stopPeerDiscovery() {
        stopDiscovery()
    }

    /**
     * Stops service discovery.
     */
    fun stopDiscovery() {
        synchronized(resolveQueue) {
            resolveQueue.clear()
            queueHandler.removeCallbacksAndMessages(null)
            unregisterActiveInfoCallback()
            currentRequest = null
            isResolving = false
        }
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Failed to stop discovery", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }
        discoveryListener = null
        peerListener = null
        releaseMulticastLock()
    }

    private fun sanitizeDeviceName(name: String): String {
        val sanitized = name.replace("ß", "ss").replace("ẞ", "Ss")
        val normalized = Normalizer.normalize(sanitized, Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
    }

    private fun getDeviceName(): String {
        val context = moduleContext?.androidContext ?: return Build.MODEL
        return android.provider.Settings.Global.getString(
            context.contentResolver,
            android.provider.Settings.Global.DEVICE_NAME,
        ) ?: Build.MODEL
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        state["discovery"] = mapOf(
            "enabled" to true,
            "peersFound" to discoveredDevices.size
        )
    }
}
