package com.pocketnode.server.hardware

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkAddress(val label: String, val ip: String)

data class DeviceSnapshot(
    val socModel: String,
    val cpuCores: Int,
    val recommendedThreads: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val addresses: List<NetworkAddress>,
)

/** Liest Hardware- und Netzwerkdaten des Geräts. */
class DeviceInfo(private val context: Context) {
    fun snapshot(): DeviceSnapshot {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return DeviceSnapshot(
            socModel = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE,
            cpuCores = cores,
            recommendedThreads = recommendedThreads(maxFrequencies(cores)),
            totalRamMb = memory.totalMem / MB,
            availableRamMb = memory.availMem / MB,
            addresses = addresses(),
        )
    }

    fun recommendedThreads(): Int = recommendedThreads(maxFrequencies(Runtime.getRuntime().availableProcessors()))

    private fun maxFrequencies(cores: Int): List<Long> = (0 until cores).mapNotNull { index ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }.getOrNull()
    }

    /** IPv4 des aktiven WLAN- oder Ethernet-Netzes, dazu Tailscale-Adressen (100.64.0.0/10). Keine Mobilfunk-Adressen. */
    private fun addresses(): List<NetworkAddress> {
        val result = mutableListOf<NetworkAddress>()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching
            val caps = connectivity.getNetworkCapabilities(network) ?: return@runCatching
            val label = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> return@runCatching
            }
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.filter { !it.isLoopbackAddress }
                ?.forEach { result += NetworkAddress(label, it.hostAddress ?: return@forEach) }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && (it.name.startsWith("tailscale") || it.name.startsWith("tun")) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { isCgnat(it) }
                .forEach { address -> address.hostAddress?.let { result += NetworkAddress("Tailscale", it) } }
        }
        return result.distinctBy { it.ip }
    }

    private fun isCgnat(address: Inet4Address): Boolean {
        val bytes = address.address
        return (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
