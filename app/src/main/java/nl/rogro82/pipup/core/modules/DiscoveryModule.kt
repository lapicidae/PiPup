package nl.rogro82.pipup.core.modules

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.PiPupModule

/**
 * Module responsible for Network Service Discovery (NSD).
 * Handles both registering this device and discovering other PiPup instances.
 */
class DiscoveryModule : PiPupModule {

    companion object {
        private const val TAG = "DiscoveryModule"
        private const val SERVICE_TYPE = "_pipup._tcp."
    }

    override val id: String = "discovery"
    override val name: String = "Network Discovery"
    override val descriptionRes: Int = R.string.settings_module_discovery_desc

    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.OFF, ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.ON

    private var moduleContext: ModuleContext? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredDevices = mutableMapOf<String, NsdServiceInfo>()

    interface DeviceListener {
        fun onDeviceFound(serviceInfo: NsdServiceInfo)
        fun onDeviceLost(serviceInfo: NsdServiceInfo)
    }

    private var deviceListener: DeviceListener? = null
    private var mLocalServiceName: String? = null

    // Queue for resolving services to avoid NsdManager bottlenecks
    private data class ResolveRequest(val service: NsdServiceInfo, var retries: Int = 0)
    private val resolveQueue = java.util.ArrayDeque<ResolveRequest>()
    private var isResolving = false
    private val queueHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentRequest: ResolveRequest? = null

    private val resolveTimeoutRunnable = Runnable {
        currentRequest?.let { req ->
            Log.w(TAG, "Resolve timed out for ${req.service.serviceName} (Try ${req.retries + 1}/3)")
            if (req.retries < 2) {
                req.retries++
                synchronized(resolveQueue) { resolveQueue.add(req) }
            }
        }
        finishResolve()
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
        moduleContext = null
        nsdManager = null
    }

    private fun registerService() {
        val context = moduleContext ?: return
        val nsd = nsdManager ?: return
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "PiPup ${getDeviceName()}".take(63)
                serviceType = SERVICE_TYPE
                port = 7979
                // Attributes for easier identification
                setAttribute("id", context.settings.deviceId)
                setAttribute("name", getDeviceName())
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister NSD service", e)
            }
        }
        registrationListener = null
    }

    /**
     * Starts discovering other PiPup services on the local network.
     */
    fun startDiscovery(listener: DeviceListener) {
        val nsd = nsdManager ?: return
        if (discoveryListener != null) stopDiscovery()

        this.deviceListener = listener
        discoveredDevices.clear()

        synchronized(resolveQueue) {
            resolveQueue.clear()
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
                        if (resolveQueue.none { it.service.serviceName == service.serviceName }) {
                            resolveQueue.add(ResolveRequest(service))
                            processNextInQueue()
                        }
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                discoveredDevices.remove(service.serviceName)
                deviceListener?.onDeviceLost(service)
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
            if (isResolving || resolveQueue.isEmpty()) return

            val nextRequest = resolveQueue.poll() ?: return
            currentRequest = nextRequest
            isResolving = true

            Log.d(TAG, "Next in queue: ${nextRequest.service.serviceName} (Try ${nextRequest.retries + 1}), waiting 300ms...")

            queueHandler.postDelayed({
                resolveServiceInternal(nextRequest.service)
            }, 300)
        }
    }

    private fun resolveServiceInternal(service: NsdServiceInfo) {
        val nsd = nsdManager ?: return
        val context = moduleContext?.androidContext ?: return
        Log.d(TAG, "Starting resolution for: ${service.serviceName}")

        // Start timeout watchdog (5s for slow emulators)
        queueHandler.postDelayed(resolveTimeoutRunnable, 5000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsd.registerServiceInfoCallback(service, ContextCompat.getMainExecutor(context), object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    onResolveFinished(serviceInfo)
                    try { nsd.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                }
                override fun onServiceLost() { finishResolve() }
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    Log.e(TAG, "Callback registration failed: $errorCode")
                    finishResolve()
                }
                override fun onServiceInfoCallbackUnregistered() {}
            })
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode")
                    finishResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    onResolveFinished(serviceInfo)
                }
            })
        }
    }

    private fun onResolveFinished(serviceInfo: NsdServiceInfo) {
        val hostAddress = serviceInfo.getHostAddress()
        Log.d(TAG, "Service resolved successfully: ${serviceInfo.serviceName} -> $hostAddress")
        discoveredDevices[serviceInfo.serviceName] = serviceInfo
        deviceListener?.onDeviceFound(serviceInfo)
        finishResolve()
    }

    private fun finishResolve() {
        queueHandler.removeCallbacks(resolveTimeoutRunnable)
        currentRequest = null
        isResolving = false
        processNextInQueue()
    }

    private fun NsdServiceInfo.getHostAddress(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            host?.hostAddress
        }
    }

    /**
     * Stops service discovery.
     */
    fun stopDiscovery() {
        synchronized(resolveQueue) {
            resolveQueue.clear()
            queueHandler.removeCallbacks(resolveTimeoutRunnable)
            isResolving = false
        }
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }
        discoveryListener = null
        deviceListener = null
    }

    private fun getDeviceName(): String {
        val context = moduleContext?.androidContext ?: return Build.MODEL
        return android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        state["discovery"] = mapOf(
            "enabled" to true,
            "peersFound" to discoveredDevices.size
        )
    }
}
