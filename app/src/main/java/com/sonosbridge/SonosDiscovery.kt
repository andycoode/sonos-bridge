package com.sonosbridge

import android.util.Log
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.*
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Discovers Sonos speakers on the local network using SSDP (Simple Service Discovery Protocol).
 *
 * Sends a multicast M-SEARCH request and listens for responses from Sonos devices.
 * Then fetches each device's description XML to get its friendly name.
 */
object SonosDiscovery {

    private const val TAG = "SonosDiscovery"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900

    // Sonos devices respond to this search target
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

    private val M_SEARCH_MESSAGE = """
        M-SEARCH * HTTP/1.1
        HOST: $SSDP_ADDRESS:$SSDP_PORT
        MAN: "ssdp:discover"
        MX: 3
        ST: $SEARCH_TARGET
        
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    /**
     * Discover Sonos speakers. Returns a list of found speakers with name and IP.
     */
    fun discover(timeoutMs: Int = 5000): List<SonosController.SonosSpeaker> {
        val speakers = mutableMapOf<String, SonosController.SonosSpeaker>()

        try {
            val socket = DatagramSocket().apply {
                soTimeout = timeoutMs
                broadcast = true
            }

            // Send M-SEARCH
            val message = M_SEARCH_MESSAGE.toByteArray()
            val address = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(message, message.size, address, SSDP_PORT)

            // Send a few times for reliability (UDP can drop packets)
            repeat(3) {
                socket.send(packet)
                Thread.sleep(100)
            }

            // Listen for responses
            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    val responseText = String(response.data, 0, response.length)
                    val ip = response.address.hostAddress ?: continue

                    // Parse LOCATION header to get device description URL
                    val locationUrl = parseLocation(responseText) ?: continue

                    // Only process each IP once
                    if (ip !in speakers) {
                        val name = fetchDeviceName(locationUrl) ?: "Sonos ($ip)"
                        val port = 1400  // Sonos control port

                        speakers[ip] = SonosController.SonosSpeaker(
                            name = name,
                            ip = ip,
                            port = port
                        )
                        Log.d(TAG, "Found: $name at $ip")
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }

            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        }

        return speakers.values.toList()
    }

    /**
     * Fetch the friendly name of a Sonos speaker by its IP address.
     * Used for manual IP entry — connects directly to the device description XML.
     * Returns null if the device is unreachable or not a Sonos speaker.
     */
    fun fetchSpeakerName(ip: String): String? {
        return fetchDeviceName("http://$ip:1400/xml/device_description.xml")
    }

    /**
     * Parse the LOCATION header from an SSDP response.
     */
    private fun parseLocation(response: String): String? {
        for (line in response.lines()) {
            if (line.startsWith("LOCATION:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    /**
     * Fetch the device description XML and extract the friendly name.
     */
    private fun fetchDeviceName(locationUrl: String): String? {
        return try {
            val connection = URL(locationUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val xml = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            // Parse XML to get friendlyName
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xml)))

            val nameNodes = doc.getElementsByTagName("friendlyName")
            if (nameNodes.length > 0) {
                nameNodes.item(0).textContent
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch device name from $locationUrl", e)
            null
        }
    }
}
