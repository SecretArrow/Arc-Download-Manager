package com.arc.downloadmanager.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes connectivity so the engine can pause and resume tasks appropriately.
 *
 * "Online" means the active network advertises NET_CAPABILITY_INTERNET. NET_CAPABILITY_VALIDATED
 * is deliberately NOT required: validation lags behind actual connectivity on many devices and
 * can stay unvalidated on VPNs, which used to strand newly added tasks in WAITING_NETWORK until
 * the user toggled Wi-Fi. A genuinely dead network now fails fast into the (configurable)
 * auto-retry flow instead of getting stuck.
 *
 * The cached StateFlows are refreshed by network callbacks AND by [refreshNow], which the engine
 * ticker calls every cycle — so a missed/late callback can no longer wedge the queue.
 */
@Singleton
class NetworkMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ConnectivityView {
        private val connectivityManager: ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val _isOnline = MutableStateFlow(false)
        override val isOnline: StateFlow<Boolean> = _isOnline

        private val _isOnWifi = MutableStateFlow(false)
        override val isOnWifi: StateFlow<Boolean> = _isOnWifi

        private val _isMetered = MutableStateFlow(true)
        val isMetered: StateFlow<Boolean> = _isMetered

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNow()
                }

                override fun onLost(network: Network) {
                    refreshNow()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    refreshNow()
                }
            }

        init {
            runCatching {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    callback,
                )
            }
            refreshNow()
        }

        /** Re-reads the live connectivity state and republishes it (self-healing poll). */
        override fun refreshNow() {
            val (online, wifi) = currentState()
            _isOnline.value = online
            _isOnWifi.value = wifi
            _isMetered.value = !wifi
        }

        /** Returns (online, onWifi) for the currently active network. */
        private fun currentState(): Pair<Boolean, Boolean> {
            val network = connectivityManager.activeNetwork ?: return false to false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false to false
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val wifi =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            return online to wifi
        }
    }
