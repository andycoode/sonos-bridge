package com.sonosbridge

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Controls Sonos speakers via their local UPnP/SOAP API.
 *
 * Sonos speakers expose HTTP endpoints on port 1400 that accept
 * SOAP XML commands for transport control (play, stop, set URI, etc).
 */
object SonosController {

    private const val TAG = "SonosController"

    data class SonosSpeaker(
        val name: String,
        val ip: String,
        val port: Int = 1400
    )

    /**
     * Tell Sonos to play audio from the given HTTP stream URL.
     */
    fun play(speaker: SonosSpeaker, streamUrl: String) {
        // Step 1: Set the audio source URI
        setAVTransportURI(speaker, streamUrl)

        // Small delay to let Sonos process the URI
        Thread.sleep(500)

        // Step 2: Start playback
        val playBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<Speed>1</Speed>
</u:Play>
</s:Body>
</s:Envelope>"""

        sendSoapRequest(
            speaker,
            "/MediaRenderer/AVTransport/Control",
            "urn:schemas-upnp-org:service:AVTransport:1#Play",
            playBody
        )

        Log.d(TAG, "Play command sent to ${speaker.name}")
    }

    /**
     * Stop playback on the Sonos speaker.
     */
    fun stop(speaker: SonosSpeaker) {
        val stopBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
</u:Stop>
</s:Body>
</s:Envelope>"""

        sendSoapRequest(
            speaker,
            "/MediaRenderer/AVTransport/Control",
            "urn:schemas-upnp-org:service:AVTransport:1#Stop",
            stopBody
        )

        Log.d(TAG, "Stop command sent to ${speaker.name}")
    }

    /**
     * Set the audio source URI on the Sonos speaker.
     * Uses plain HTTP URL with minimal metadata.
     */
    private fun setAVTransportURI(speaker: SonosSpeaker, streamUrl: String) {
        val setUriBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>${streamUrl}</CurrentURI>
<CurrentURIMetaData></CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""

        sendSoapRequest(
            speaker,
            "/MediaRenderer/AVTransport/Control",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            setUriBody
        )

        Log.d(TAG, "Set URI: $streamUrl on ${speaker.name}")
    }

    /**
     * Set volume on the Sonos speaker (0-100).
     */
    fun setVolume(speaker: SonosSpeaker, volume: Int) {
        val clampedVolume = volume.coerceIn(0, 100)

        val volumeBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
<InstanceID>0</InstanceID>
<Channel>Master</Channel>
<DesiredVolume>${clampedVolume}</DesiredVolume>
</u:SetVolume>
</s:Body>
</s:Envelope>"""

        sendSoapRequest(
            speaker,
            "/MediaRenderer/RenderingControl/Control",
            "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume",
            volumeBody
        )
    }

    /**
     * Send a raw SOAP request to the Sonos speaker.
     */
    private fun sendSoapRequest(
        speaker: SonosSpeaker,
        path: String,
        soapAction: String,
        body: String
    ) {
        val url = URL("http://${speaker.ip}:${speaker.port}$path")

        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPAction", "\"$soapAction\"")

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(body)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                Log.d(TAG, "SOAP request successful: $soapAction")
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "SOAP error ($responseCode) for $soapAction: $error")
            }

            connection.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "SOAP request failed: ${e.message}", e)
            throw e
        }
    }
}
