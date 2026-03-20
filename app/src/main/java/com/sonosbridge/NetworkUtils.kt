package com.sonosbridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Network utilities for getting the device's local IP address.
 * The Sonos speaker needs to connect back to us, so we need our LAN IP.
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * Get the device's local WiFi IP address.
     * Tries WifiManager first (reliable on Fire Stick), falls back to
     * iterating network interfaces.
     */
    fun getLocalIpAddress(context: Context): String {
        // Method 1: WifiManager (most reliable on Fire TV)
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress

            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    (ipInt shr 8) and 0xff,
                    (ipInt shr 16) and 0xff,
                    (ipInt shr 24) and 0xff
                )
                Log.d(TAG, "WiFi IP: $ip")
                return ip
            }
        } catch (e: Exception) {
            Log.e(TAG, "WifiManager failed", e)
        }

        // Method 2: NetworkInterface enumeration
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        // Filter for IPv4
                        if (hostAddress.indexOf(':') < 0) {
                            Log.d(TAG, "Interface ${intf.name} IP: $hostAddress")
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NetworkInterface enumeration failed", e)
        }

        throw RuntimeException("Could not determine local IP address. Is WiFi connected?")
    }
}
