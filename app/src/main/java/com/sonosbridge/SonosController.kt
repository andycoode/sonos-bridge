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
     *
     * Critically, we send DIDL-Lite metadata declaring the stream as
     * `object.item.audioItem.audioBroadcast` (a LIVE broadcast). Without this, Sonos
     * treats the WAV as a downloadable file and buffers it aggressively — seconds of
     * latency that only grow as the capture/playback clocks drift apart. Declaring it
     * a broadcast switches Sonos into continuous live-stream mode with much smaller
     * internal buffers, which is what keeps the audio close to real-time.
     */
    private fun setAVTransportURI(speaker: SonosSpeaker, streamUrl: String) {
        // DIDL-Lite document describing the stream. This is itself XML, so it must be
        // XML-escaped before being embedded as the text of <CurrentURIMetaData>.
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/"><item id="sonos-bridge-live" parentID="-1" restricted="true"><dc:title>Sonos Bridge Live</dc:title><upnp:class>object.item.audioItem.audioBroadcast</upnp:class><desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">RINCON_AssociatedZPUDN</desc></item></DIDL-Lite>"""

        val setUriBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
<InstanceID>0</InstanceID>
<CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>
<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""

        sendSoapRequest(
            speaker,
            "/MediaRenderer/AVTransport/Control",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            setUriBody
        )

        Log.d(TAG, "Set URI (live broadcast): $streamUrl on ${speaker.name}")
    }

    /**
     * Escape a string so it is safe to embed as XML text content.
     * `&` must be replaced first so we don't double-escape the others.
     */
    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

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
