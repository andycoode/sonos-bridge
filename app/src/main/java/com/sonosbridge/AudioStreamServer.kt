package com.sonosbridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight HTTP server that serves a live WAV audio stream.
 *
 * When Sonos connects to /audio.wav, it receives:
 * 1. A WAV header with unknown length (0xFFFFFFFF) - signals "live stream"
 * 2. Continuous raw PCM data as it's captured
 *
 * Latency optimisations:
 * - No buffering between capture and output
 * - WAV format = no encoding overhead
 * - Unknown-length WAV header tells Sonos to treat as live (smaller internal buffer)
 * - Uses piped streams for zero-copy data flow
 */
class AudioStreamServer : NanoHTTPD(PORT) {

    companion object {
        private const val TAG = "StreamServer"
        const val PORT = 8087  // Arbitrary high port, unlikely to conflict
    }

    // Track all active client connections (Sonos may reconnect)
    private val activeStreams = CopyOnWriteArrayList<PipedOutputStream>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: ${session.method} $uri from ${session.remoteIpAddress}")

        return when {
            uri == "/audio.wav" -> serveAudioStream()
            uri == "/ping" -> servePing()
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found"
            )
        }
    }

    /**
     * Serve a live WAV audio stream.
     *
     * Creates a piped stream pair:
     * - PipedOutputStream: receives PCM data from the capture service
     * - PipedInputStream: NanoHTTPD reads from this and sends to Sonos
     *
     * The WAV header declares the data length as 0xFFFFFFFF (unknown),
     * which is the standard way to signal a live/infinite stream.
     */
    private fun serveAudioStream(): Response {
        try {
            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut, 8192)  // Small pipe buffer

            activeStreams.add(pipedOut)
            Log.d(TAG, "New stream client connected (total: ${activeStreams.size})")

            // Write WAV header to the pipe first
            val wavHeader = createWavHeader()
            pipedOut.write(wavHeader)
            pipedOut.flush()

            // Create a response that streams from the piped input
            val response = newChunkedResponse(
                Response.Status.OK,
                "audio/wav",
                pipedIn
            )

            // Set headers to discourage caching and signal live content
            response.addHeader("Cache-Control", "no-cache, no-store")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Connection", "close")
            response.addHeader("Transfer-Encoding", "chunked")
            // ICY metadata header - helps some players treat as live
            response.addHeader("icy-name", "Sonos Bridge Live Audio")

            return response

        } catch (e: Exception) {
            Log.e(TAG, "Error creating audio stream", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Stream error: ${e.message}"
            )
        }
    }

    /**
     * Health check endpoint - Sonos or the app can ping to verify server is alive.
     */
    private fun servePing(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_PLAINTEXT,
            "ok"
        )
    }

    /**
     * Called by AudioCaptureService to push captured PCM data
     * to all connected Sonos clients.
     *
     * This is the hot path - called every ~20ms with a buffer of PCM data.
     * Must be fast with no allocations.
     */
    fun pushAudioData(data: ByteArray, length: Int) {
        val deadStreams = mutableListOf<PipedOutputStream>()

        for (stream in activeStreams) {
            try {
                stream.write(data, 0, length)
                stream.flush()  // Flush immediately - don't let the pipe buffer
            } catch (e: Exception) {
                // Client disconnected
                Log.d(TAG, "Stream client disconnected")
                deadStreams.add(stream)
            }
        }

        // Clean up disconnected clients
        if (deadStreams.isNotEmpty()) {
            activeStreams.removeAll(deadStreams.toSet())
            for (stream in deadStreams) {
                try { stream.close() } catch (_: Exception) {}
            }
            Log.d(TAG, "Cleaned up ${deadStreams.size} dead stream(s), ${activeStreams.size} remaining")
        }
    }

    /**
     * Close all active streams (when stopping).
     */
    fun closeAllStreams() {
        for (stream in activeStreams) {
            try { stream.close() } catch (_: Exception) {}
        }
        activeStreams.clear()
    }

    /**
     * Create a WAV file header for a live PCM stream.
     *
     * Key detail: both the RIFF chunk size and data chunk size are set to
     * 0xFFFFFFFF, which signals "unknown length" / streaming. This is
     * important because it tells Sonos not to try to determine total
     * duration, and to use its live stream buffering strategy (lower latency).
     */
    private fun createWavHeader(): ByteArray {
        val sampleRate = AudioCaptureService.SAMPLE_RATE
        val channels = AudioCaptureService.CHANNELS
        val bitsPerSample = AudioCaptureService.BITS_PER_SAMPLE
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        var offset = 0

        fun writeString(s: String) {
            for (c in s) header[offset++] = c.code.toByte()
        }

        fun writeInt(v: Int) {
            header[offset++] = (v and 0xFF).toByte()
            header[offset++] = ((v shr 8) and 0xFF).toByte()
            header[offset++] = ((v shr 16) and 0xFF).toByte()
            header[offset++] = ((v shr 24) and 0xFF).toByte()
        }

        fun writeShort(v: Int) {
            header[offset++] = (v and 0xFF).toByte()
            header[offset++] = ((v shr 8) and 0xFF).toByte()
        }

        // RIFF header
        writeString("RIFF")
        writeInt(0x7FFFFFFF)        // Chunk size: max int = "unknown/streaming"
        writeString("WAVE")

        // fmt sub-chunk
        writeString("fmt ")
        writeInt(16)                // Sub-chunk size (PCM = 16)
        writeShort(1)               // Audio format: 1 = PCM (no compression)
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(bitsPerSample)

        // data sub-chunk
        writeString("data")
        writeInt(0x7FFFFFFF)        // Data size: max int = "streaming"

        return header
    }
}
