package com.sonosbridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.IOException

/**
 * Foreground service that captures system audio using AudioPlaybackCapture API.
 *
 * Features:
 * - Wake locks to prevent CPU/WiFi throttling
 * - Periodic stream refresh every 25 minutes to prevent drift
 * - Auto-reconnect watchdog if Sonos disconnects
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val NOTIFICATION_CHANNEL_ID = "sonos_bridge_capture"
        private const val NOTIFICATION_ID = 1

        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16

        // Refresh the Sonos connection every 25 minutes to prevent drift
        private const val REFRESH_INTERVAL_MS = 25L * 60 * 1000

        // Check if Sonos is still connected every 15 seconds
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        // If Sonos hasn't been connected for this long, try reconnecting
        private const val RECONNECT_AFTER_MS = 30_000L
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var streamServer: AudioStreamServer? = null
    private var captureJob: Job? = null
    private var watchdogJob: Job? = null
    private var refreshJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var videoDelayMs: Long = 1500

    // Sonos connection details (set from MainActivity)
    private var sonosIp: String? = null
    private var sonosPort: Int = 1400
    private var streamUrl: String? = null

    // Tracking for watchdog
    private var lastClientSeenTime: Long = 0
    private var isRunning = false

    // Wake locks
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureService = this@AudioCaptureService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val projectionData = intent.getParcelableExtra<Intent>("projectionData")
        videoDelayMs = intent.getLongExtra("videoDelay", 1500)
        sonosIp = intent.getStringExtra("sonosIp")
        sonosPort = intent.getIntExtra("sonosPort", 1400)

        if (resultCode == Activity.RESULT_CANCELED || projectionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Stop any existing capture first
        captureJob?.cancel()
        captureJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        refreshJob?.cancel()
        refreshJob = null
        audioRecord?.apply {
            try { stop(); release() } catch (_: Exception) {}
        }
        audioRecord = null
        streamServer?.apply {
            closeAllStreams()
            stop()
        }
        streamServer = null

        // Create notification channel and start foreground
        createNotificationChannel()
        val notification = buildNotification("Initialising audio capture...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Acquire wake locks
        acquireWakeLocks()

        // Start the HTTP stream server
        startStreamServer()

        // Get MediaProjection and start capturing
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopCapture()
            }
        }, null)

        startAudioCapture()

        // Calculate stream URL
        try {
            val localIp = NetworkUtils.getLocalIpAddress(this)
            streamUrl = "http://$localIp:${AudioStreamServer.PORT}/audio.wav"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
        }

        isRunning = true
        lastClientSeenTime = System.currentTimeMillis()

        // Start the watchdog — monitors Sonos connection
        startWatchdog()

        // Start the periodic refresh — prevents drift
        startPeriodicRefresh()

        return START_NOT_STICKY
    }

    private fun startStreamServer() {
        try {
            streamServer = AudioStreamServer()
            streamServer?.start()
            Log.d(TAG, "Stream server started on port ${AudioStreamServer.PORT}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start stream server", e)
        }
    }

    private fun startAudioCapture() {
        val projection = mediaProjection ?: return

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        val bufferSize = minBufferSize * 8

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise")
                updateNotification("Capture failed - permission issue?")
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Audio capture started (buffer: ${bufferSize} bytes, ~${bufferSize * 1000 / (SAMPLE_RATE * CHANNELS * 2)}ms)")

            updateNotification("Streaming audio to Sonos")

            captureJob = scope.launch {
                captureLoop(bufferSize)
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - capture not permitted", e)
            updateNotification("Capture not permitted for this app")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            updateNotification("Capture error: ${e.message}")
        }
    }

    private suspend fun captureLoop(bufferSize: Int) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        val buffer = ByteArray(bufferSize)

        while (coroutineContext.isActive) {
            val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

            when {
                bytesRead > 0 -> {
                    streamServer?.pushAudioData(buffer, bytesRead)

                    // Track that we're actively pushing data
                    if (streamServer?.hasActiveClients() == true) {
                        lastClientSeenTime = System.currentTimeMillis()
                    }
                }
                bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "AudioRecord: invalid operation")
                    break
                }
                bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "AudioRecord: bad value")
                    break
                }
                bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                    Log.e(TAG, "AudioRecord: dead object")
                    break
                }
            }
        }

        Log.d(TAG, "Capture loop ended")
    }

    /**
     * Watchdog: checks every 15 seconds if the Sonos is still connected.
     * If no client has been seen for 30 seconds, re-sends the play command.
     */
    private fun startWatchdog() {
        watchdogJob = scope.launch {
            // Give initial connection time to establish
            delay(RECONNECT_AFTER_MS)

            while (isActive && isRunning) {
                delay(WATCHDOG_INTERVAL_MS)

                val ip = sonosIp ?: continue
                val url = streamUrl ?: continue

                val timeSinceLastClient = System.currentTimeMillis() - lastClientSeenTime

                if (timeSinceLastClient > RECONNECT_AFTER_MS) {
                    Log.w(TAG, "Watchdog: No client for ${timeSinceLastClient}ms — reconnecting Sonos")
                    updateNotification("Reconnecting to Sonos...")

                    try {
                        val speaker = SonosController.SonosSpeaker(name = "Sonos", ip = ip, port = sonosPort)
                        SonosController.play(speaker, url)
                        lastClientSeenTime = System.currentTimeMillis()
                        Log.d(TAG, "Watchdog: Reconnect command sent")
                        updateNotification("Streaming audio to Sonos")
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog: Reconnect failed: ${e.message}")
                        updateNotification("Reconnect failed — retrying...")
                    }
                } else {
                    val clients = streamServer?.clientCount() ?: 0
                    Log.d(TAG, "Watchdog: OK — $clients client(s), last seen ${timeSinceLastClient}ms ago")
                }
            }
        }
    }

    /**
     * Periodic refresh: every 25 minutes, tell the Sonos to stop and restart
     * playback. This resets any accumulated timing drift in the stream.
     * Results in ~2-3 seconds of silence during the refresh.
     */
    private fun startPeriodicRefresh() {
        refreshJob = scope.launch {
            while (isActive && isRunning) {
                delay(REFRESH_INTERVAL_MS)

                val ip = sonosIp ?: continue
                val url = streamUrl ?: continue

                Log.d(TAG, "Periodic refresh: resetting Sonos stream")
                updateNotification("Refreshing stream...")

                try {
                    val speaker = SonosController.SonosSpeaker(name = "Sonos", ip = ip, port = sonosPort)

                    // Stop current playback
                    try { SonosController.stop(speaker) } catch (_: Exception) {}

                    // Clear the ring buffer so Sonos gets fresh audio
                    streamServer?.closeAllStreams()

                    // Brief pause to let Sonos process the stop
                    delay(1500)

                    // Restart playback
                    SonosController.play(speaker, url)
                    lastClientSeenTime = System.currentTimeMillis()

                    Log.d(TAG, "Periodic refresh: stream restarted")
                    updateNotification("Streaming audio to Sonos")
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic refresh failed: ${e.message}")
                    updateNotification("Refresh failed — watchdog will retry")
                }
            }
        }
    }

    fun setVideoDelay(delayMs: Long) {
        videoDelayMs = delayMs
        Log.d(TAG, "Video delay set to ${delayMs}ms")
    }

    fun startCalibration() {
        Log.d(TAG, "Calibration started")
    }

    private fun stopCapture() {
        isRunning = false

        watchdogJob?.cancel()
        watchdogJob = null
        refreshJob?.cancel()
        refreshJob = null
        captureJob?.cancel()
        captureJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (_: Exception) {}
        }
        audioRecord = null

        streamServer?.apply {
            closeAllStreams()
            stop()
        }
        streamServer = null

        mediaProjection?.stop()
        mediaProjection = null

        releaseWakeLocks()
    }

    private fun acquireWakeLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SonosBridge::AudioCapture"
            ).apply {
                acquire()
            }
            Log.d(TAG, "CPU wake lock acquired")

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SonosBridge::AudioStream"
            ).apply {
                acquire()
            }
            Log.d(TAG, "WiFi high-perf lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake locks: ${e.message}", e)
        }
    }

    private fun releaseWakeLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "CPU wake lock released")
                }
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WiFi lock released")
                }
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake locks: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sonos Audio Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when audio is being streamed to Sonos"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Sonos Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        scope.cancel()
    }
}
