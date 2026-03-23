package com.sonosbridge

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HTTP server that serves a live WAV audio stream using a ring buffer.
 *
 * The ring buffer approach prevents the stuttering that occurs with piped streams
 * over long sessions. When audio capture produces data faster than Sonos consumes
 * it (or vice versa due to timing drift), the ring buffer handles it gracefully:
 * - If the buffer fills up, oldest audio is silently dropped (prevents backlog)
 * - If Sonos reads faster than capture produces, it blocks briefly (prevents underrun)
 * - The stream self-corrects after any network hiccup instead of getting permanently out of sync
 */
class AudioStreamServer : NanoHTTPD(PORT) {

    companion object {
        private const val TAG = "StreamServer"
        const val PORT = 8087

        // Ring buffer size: ~2 seconds of CD-quality stereo audio
        // 44100 samples/sec * 2 channels * 2 bytes/sample * 2 seconds = 352,800 bytes
        private const val RING_BUFFER_SIZE = 352800
    }

    private val activeClients = CopyOnWriteArrayList<RingBufferStream>()

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

    private fun serveAudioStream(): Response {
        try {
            val client = RingBufferStream(RING_BUFFER_SIZE)
            activeClients.add(client)
            Log.d(TAG, "New stream client connected (total: ${activeClients.size})")

            // Write WAV header as the first thing the client receives
            val wavHeader = createWavHeader()
            client.writeData(wavHeader, wavHeader.size)

            val response = newChunkedResponse(
                Response.Status.OK,
                "audio/wav",
                client
            )

            response.addHeader("Cache-Control", "no-cache, no-store")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Connection", "close")
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

    private fun servePing(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_PLAINTEXT,
            "ok"
        )
    }

    /**
     * Called by AudioCaptureService to push captured PCM data to all connected clients.
     * Hot path — called every ~40ms.
     */
    fun pushAudioData(data: ByteArray, length: Int) {
        val deadClients = mutableListOf<RingBufferStream>()

        for (client in activeClients) {
            if (client.isClosed) {
                deadClients.add(client)
            } else {
                client.writeData(data, length)
            }
        }

        if (deadClients.isNotEmpty()) {
            activeClients.removeAll(deadClients.toSet())
            Log.d(TAG, "Cleaned up ${deadClients.size} dead client(s), ${activeClients.size} remaining")
        }
    }

    fun closeAllStreams() {
        for (client in activeClients) {
            client.close()
        }
        activeClients.clear()
    }

    /**
     * Ring buffer backed InputStream.
     *
     * Write side (audio capture) and read side (NanoHTTPD/Sonos) operate independently.
     * If write overtakes read, oldest data is dropped — this prevents the buffer from
     * ever growing unbounded and keeps the stream close to real-time.
     */
    class RingBufferStream(private val capacity: Int) : InputStream() {
        private val buffer = ByteArray(capacity)
        private var writePos = 0        // Next position to write to
        private var readPos = 0         // Next position to read from
        private var available = 0       // Bytes available to read
        private val lock = Object()
        @Volatile var isClosed = false
            private set

        /**
         * Write audio data into the ring buffer.
         * If the buffer is full, oldest data is silently overwritten.
         * This is the key difference from PipedOutputStream — it never blocks the writer.
         */
        fun writeData(data: ByteArray, length: Int) {
            if (isClosed) return

            synchronized(lock) {
                for (i in 0 until length) {
                    buffer[writePos] = data[i]
                    writePos = (writePos + 1) % capacity

                    if (available < capacity) {
                        available++
                    } else {
                        // Buffer full — advance read position (drop oldest byte)
                        readPos = (readPos + 1) % capacity
                    }
                }
                lock.notifyAll()
            }
        }

        /**
         * Read from the ring buffer. Blocks if no data is available yet.
         * Called by NanoHTTPD's response streamer thread.
         */
        override fun read(): Int {
            synchronized(lock) {
                while (available == 0 && !isClosed) {
                    try {
                        lock.wait(100)  // Wake up periodically to check if closed
                    } catch (_: InterruptedException) {
                        return -1
                    }
                }

                if (available == 0 && isClosed) return -1

                val byte = buffer[readPos].toInt() and 0xFF
                readPos = (readPos + 1) % capacity
                available--
                return byte
            }
        }

        /**
         * Bulk read for better performance — reads up to len bytes at once.
         */
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0

            synchronized(lock) {
                // Wait for at least some data
                while (available == 0 && !isClosed) {
                    try {
                        lock.wait(100)
                    } catch (_: InterruptedException) {
                        return -1
                    }
                }

                if (available == 0 && isClosed) return -1

                // Read as much as we can, up to len bytes
                val toRead = minOf(len, available)
                for (i in 0 until toRead) {
                    b[off + i] = buffer[readPos]
                    readPos = (readPos + 1) % capacity
                }
                available -= toRead
                return toRead
            }
        }

        override fun available(): Int {
            synchronized(lock) {
                return available
            }
        }

        override fun close() {
            isClosed = true
            synchronized(lock) {
                lock.notifyAll()
            }
        }
    }

    /**
     * Create a WAV header for a live PCM stream.
     * Data length set to max int to signal "streaming/unknown length".
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

        writeString("RIFF")
        writeInt(0x7FFFFFFF)
        writeString("WAVE")

        writeString("fmt ")
        writeInt(16)
        writeShort(1)               // PCM
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(blockAlign)
        writeShort(bitsPerSample)

        writeString("data")
        writeInt(0x7FFFFFFF)

        return header
    }
}
