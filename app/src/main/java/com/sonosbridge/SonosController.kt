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
 *
 * To stream our audio, we:
 * 1. SetAVTransportURI - tell Sonos to load our HTTP stream URL
 * 2. Play - start playback
 *
 * The stream URL points back to our AudioStreamServer running on the Fire Stick.
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
        Thread.sleep(200)

        // Step 2: Start playback
        sendTransportCommand(speaker, "Play", """
            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent())

        Log.d(TAG, "Play command sent to ${speaker.name}")
    }

    /**
     * Stop playback on the Sonos speaker.
     */
    fun stop(speaker: SonosSpeaker) {
        sendTransportCommand(speaker, "Stop", """
            <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
            </u:Stop>
        """.trimIndent())

        Log.d(TAG, "Stop command sent to ${speaker.name}")
    }

    /**
     * Set the audio source URI on the Sonos speaker.
     *
     * We use x-rincon-mp3radio:// protocol prefix which tells Sonos
     * to treat this as a live radio stream. This is important because
     * it triggers Sonos's low-latency streaming mode with smaller buffers.
     */
    private fun setAVTransportURI(speaker: SonosSpeaker, streamUrl: String) {
        // Use x-rincon-mp3radio:// to hint at live stream behaviour
        // Even though we're serving WAV, this protocol prefix reduces buffering
        val sonosUrl = streamUrl.replace("http://", "x-rincon-mp3radio://")

        val metadata = """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
                       xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/">
                <item id="R:0/0/0" parentID="R:0/0" restricted="true">
                    <dc:title>Sonos Bridge</dc:title>
                    <upnp:class>object.item.audioItem.audioBroadcast</upnp:class>
                    <res protocolInfo="http-get:*:audio/wav:*">$streamUrl</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()

        val escapedUrl = escapeXml(sonosUrl)
        val escapedMetadata = escapeXml(metadata)

        sendTransportCommand(speaker, "SetAVTransportURI", """
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                <InstanceID>0</InstanceID>
                <CurrentURI>$escapedUrl</CurrentURI>
                <CurrentURIMetaData>$escapedMetadata</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent())

        Log.d(TAG, "Set URI: $streamUrl on ${speaker.name}")
    }

    /**
     * Set volume on the Sonos speaker (0-100).
     */
    fun setVolume(speaker: SonosSpeaker, volume: Int) {
        val clampedVolume = volume.coerceIn(0, 100)

        val body = buildSoapEnvelope("""
            <u:SetVolume xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
                <InstanceID>0</InstanceID>
                <Channel>Master</Channel>
                <DesiredVolume>$clampedVolume</DesiredVolume>
            </u:SetVolume>
        """.trimIndent())

        sendSoapRequest(
            speaker,
            "/MediaRenderer/RenderingControl/Control",
            "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume",
            body
        )
    }

    /**
     * Send a transport control command (Play, Stop, Pause, etc).
     */
    private fun sendTransportCommand(speaker: SonosSpeaker, action: String, body: String) {
        val envelope = buildSoapEnvelope(body)
        sendSoapRequest(
            speaker,
            "/MediaRenderer/AVTransport/Control",
            "urn:schemas-upnp-org:service:AVTransport:1#$action",
            envelope
        )
    }

    /**
     * Build a complete SOAP envelope around the action body.
     */
    private fun buildSoapEnvelope(body: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    $body
                </s:Body>
            </s:Envelope>
        """.trimIndent()
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
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "SOAP error ($responseCode): $error")
            }

            connection.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "SOAP request failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Escape XML special characters.
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
