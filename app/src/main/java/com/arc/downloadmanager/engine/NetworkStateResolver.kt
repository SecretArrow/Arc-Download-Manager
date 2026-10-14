package com.arc.downloadmanager.engine

/**
 * Pure decision core for [NetworkMonitor]: picks the connectivity state from the default
 * network plus a scan of every known network.
 *
 * The default network wins while it actually provides Internet. If it is missing or claims
 * to be offline while ANY other known network still advertises NET_CAPABILITY_INTERNET, that
 * network is used instead. Some ROMs and Wi-Fi/cellular handoff windows leave activeNetwork
 * null or stale while cellular data still works — without this fallback every queued task
 * landed in WAITING_NETWORK and only a Wi-Fi toggle woke it, so downloads looked
 * "Wi-Fi only" even with the Wi-Fi-only switch off.
 */
object NetworkStateResolver {
    /** Picks (online, onWifi) from the default-network pair and all known network pairs. */
    fun resolve(
        active: Pair<Boolean, Boolean>?,
        others: List<Pair<Boolean, Boolean>>,
    ): Pair<Boolean, Boolean> {
        if (active != null && active.first) return active
        val usable = others.filter { it.first }
        // Prefer Wi-Fi among usable networks so Wi-Fi-only tasks are never held back.
        return usable.firstOrNull { it.second } ?: usable.firstOrNull() ?: (false to false)
    }
}
