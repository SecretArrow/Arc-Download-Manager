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

/** Observes connectivity so the engine can pause and resume tasks appropriately. */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityView {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(currentState().first)
    val isOnline: StateFlow<Boolean> = _isOnline

    private val _isOnWifi = MutableStateFlow(currentState().second)
    val isOnWifi: StateFlow<Boolean> = _isOnWifi

    private val _isMetered = MutableStateFlow(!currentState().second)
    val isMetered: StateFlow<Boolean> = _isMetered

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val state = currentState()
            _isOnline.value = state.first
            _isOnWifi.value = state.second
            _isMetered.value = !state.second
        }

        override fun onLost(network: Network) {
            val state = currentState()
            _isOnline.value = state.first
            _isOnWifi.value = state.second
            _isMetered.value = !state.second
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val state = currentState()
            _isOnline.value = state.first
            _isOnWifi.value = state.second
            _isMetered.value = !state.second
        }
    }

    init {
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }
    }

    /** Returns (online, onWifi) for the currently active network. */
    private fun currentState(): Pair<Boolean, Boolean> {
        val network = connectivityManager.activeNetwork ?: return false to false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false to false
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return online to wifi
    }
}
