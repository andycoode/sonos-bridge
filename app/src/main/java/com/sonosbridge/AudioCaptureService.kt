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
 * Includes wake locks to prevent Android from throttling CPU/WiFi during streaming,
 * which causes gradual audio degradation over time.
 */
class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCapture"
        private const val NOTIFICATION_CHANNEL_ID = "sonos_bridge_capture"
        private const val NOTIFICATION_ID = 1

        // Audio format - CD quality, good balance of quality vs bandwidth
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var streamServer: AudioStreamServer? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var videoDelayMs: Long = 1500

    // Wake locks to prevent power saving from killing the stream
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Binder for activity binding
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

        if (resultCode == Activity.RESULT_CANCELED || projectionData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Stop any existing server/audio capture first (in case of restart)
        captureJob?.cancel()
        captureJob = null
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

        // Acquire wake locks to prevent Android from throttling CPU/WiFi
        acquireWakeLocks()

        // Start the HTTP stream server FIRST so it's ready for data
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

        return START_NOT_STICKY
    }

    /**
     * Start the NanoHTTPD-based audio stream server.
     * Sonos will connect to this to receive the live audio.
     */
    private fun startStreamServer() {
        try {
            streamServer = AudioStreamServer()
            streamServer?.start()
            Log.d(TAG, "Stream server started on port ${AudioStreamServer.PORT}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start stream server", e)
        }
    }

    /**
     * Set up AudioRecord with AudioPlaybackCapture configuration.
     *
     * This is the magic bit - AudioPlaybackCapture lets us tap into
     * other apps' audio output at the system mixer level.
     */
    private fun startAudioCapture() {
        val projection = mediaProjection ?: return

        // Configure playback capture - grab all audio usage types
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

        // Use minimum buffer size for lowest latency
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        // Use 8x min buffer for stability on budget hardware
        // Larger buffer = fewer scheduler wakeups = less CPU pressure over time
        // Trade-off is ~160ms of capture latency, but Sonos adds 500ms+ anyway
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

            // Start the capture loop in a coroutine
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

    /**
     * Main capture loop - reads PCM data from AudioRecord and pushes
     * it to the stream server as fast as it arrives.
     *
     * Runs at elevated thread priority to prevent scheduler starvation
     * on budget hardware during long sessions.
     */
    private suspend fun captureLoop(bufferSize: Int) {
        // Elevate thread priority for real-time audio
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        val buffer = ByteArray(bufferSize)

        while (coroutineContext.isActive) {
            val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

            when {
                bytesRead > 0 -> {
                    // Push raw PCM data directly to the HTTP stream server
                    streamServer?.pushAudioData(buffer, bytesRead)
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

    fun setVideoDelay(delayMs: Long) {
        videoDelayMs = delayMs
        Log.d(TAG, "Video delay set to ${delayMs}ms")
    }

    fun startCalibration() {
        // TODO: Play a beep tone through the stream and show a visual flash
        // User adjusts delay slider until the beep and flash align
        Log.d(TAG, "Calibration started")
    }

    private fun stopCapture() {
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

    /**
     * Acquire CPU and WiFi wake locks to prevent Android from
     * throttling resources during long streaming sessions.
     * Without these, Android gradually reduces WiFi performance
     * after ~30-40 minutes, causing audio stuttering.
     */
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

    /**
     * Release all wake locks.
     */
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
                NotificationManager.IMPORTANCE_LOW  // Low = no sound, just shows in tray
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
