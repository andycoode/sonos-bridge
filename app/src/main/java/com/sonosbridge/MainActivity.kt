package com.sonosbridge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.*
import kotlinx.coroutines.*

/**
 * Main activity for Sonos Bridge.
 *
 * Designed for Fire TV Stick D-pad navigation.
 * Flow: Discover Sonos → Select speaker → Grant capture permission → Stream
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "SonosBridge"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
    }

    private lateinit var projectionManager: MediaProjectionManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var speakerInfo: TextView
    private lateinit var btnDiscover: Button
    private lateinit var btnStartStop: Button
    private lateinit var btnCalibrate: Button
    private lateinit var delaySlider: SeekBar
    private lateinit var delayLabel: TextView
    private lateinit var speakerList: ListView

    // State
    private var selectedSpeaker: SonosController.SonosSpeaker? = null
    private var isStreaming = false
    private var captureService: AudioCaptureService? = null
    private var serviceBound = false
    private var discoveredSpeakers = listOf<SonosController.SonosSpeaker>()

    // Video delay in ms (to compensate for Sonos audio buffer lag)
    private var videoDelayMs: Long = 1500

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AudioCaptureService.LocalBinder
            captureService = localBinder.getService()
            serviceBound = true
            Log.d(TAG, "AudioCaptureService bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Bind UI elements
        statusText = findViewById(R.id.statusText)
        speakerInfo = findViewById(R.id.speakerInfo)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        delaySlider = findViewById(R.id.delaySlider)
        delayLabel = findViewById(R.id.delayLabel)
        speakerList = findViewById(R.id.speakerList)

        // --- Discover Sonos speakers ---
        btnDiscover.setOnClickListener {
            discoverSpeakers()
        }

        // --- Speaker selection ---
        speakerList.setOnItemClickListener { _, _, position, _ ->
            selectedSpeaker = discoveredSpeakers[position]
            speakerInfo.text = "Selected: ${selectedSpeaker?.name} (${selectedSpeaker?.ip})"
            btnStartStop.isEnabled = true
            btnStartStop.requestFocus()
        }

        // --- Start / Stop streaming ---
        btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                requestCapturePermission()
            }
        }
        btnStartStop.isEnabled = false

        // --- Video delay calibration slider ---
        delaySlider.max = 3000  // 0 - 3000ms
        delaySlider.progress = videoDelayMs.toInt()
        delayLabel.text = "Video delay: ${videoDelayMs}ms"

        delaySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                videoDelayMs = progress.toLong()
                delayLabel.text = "Video delay: ${videoDelayMs}ms"
                captureService?.setVideoDelay(videoDelayMs)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Calibration beep test ---
        btnCalibrate.setOnClickListener {
            runCalibration()
        }

        // Bind to capture service
        Intent(this, AudioCaptureService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        updateUI()
    }

    /**
     * Discover Sonos speakers on the local network using SSDP.
     */
    private fun discoverSpeakers() {
        statusText.text = "Discovering Sonos speakers..."
        btnDiscover.isEnabled = false

        scope.launch {
            try {
                val speakers = withContext(Dispatchers.IO) {
                    SonosDiscovery.discover(timeoutMs = 5000)
                }
                discoveredSpeakers = speakers

                if (speakers.isEmpty()) {
                    statusText.text = "No Sonos speakers found. Check WiFi."
                } else {
                    statusText.text = "Found ${speakers.size} speaker(s)"
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        speakers.map { "${it.name} (${it.ip})" }
                    )
                    speakerList.adapter = adapter
                }
            } catch (e: Exception) {
                statusText.text = "Discovery failed: ${e.message}"
                Log.e(TAG, "SSDP discovery failed", e)
            } finally {
                btnDiscover.isEnabled = true
            }
        }
    }

    /**
     * Request MediaProjection permission (system dialog).
     * On Fire Stick this shows a "Start recording?" prompt.
     */
    private fun requestCapturePermission() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    startStreaming(resultCode, data)
                } else {
                    statusText.text = "Capture permission denied"
                }
            }
        }
    }

    /**
     * Start audio capture and streaming to selected Sonos speaker.
     */
    private fun startStreaming(resultCode: Int, projectionData: Intent) {
        val speaker = selectedSpeaker ?: return

        // Start the foreground service with MediaProjection data
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("projectionData", projectionData)
            putExtra("sonosIp", speaker.ip)
            putExtra("sonosPort", speaker.port)
            putExtra("videoDelay", videoDelayMs)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isStreaming = true
        updateUI()

        // Tell Sonos to start playing our stream
        scope.launch(Dispatchers.IO) {
            try {
                val localIp = NetworkUtils.getLocalIpAddress(this@MainActivity)
                val streamUrl = "http://$localIp:${AudioStreamServer.PORT}/audio.wav"

                Log.d(TAG, "Telling Sonos to play: $streamUrl")
                SonosController.play(speaker, streamUrl)

                withContext(Dispatchers.Main) {
                    statusText.text = "Streaming to ${speaker.name}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Sonos playback", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                    stopStreaming()
                }
            }
        }
    }

    private fun stopStreaming() {
        val speaker = selectedSpeaker
        if (speaker != null) {
            scope.launch(Dispatchers.IO) {
                try { SonosController.stop(speaker) } catch (_: Exception) {}
            }
        }

        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        stopService(serviceIntent)

        isStreaming = false
        updateUI()
        statusText.text = "Stopped"
    }

    /**
     * Run a latency calibration test.
     * Plays a beep through both the projector and Sonos,
     * user adjusts slider until they sync up.
     */
    private fun runCalibration() {
        statusText.text = "Playing calibration beeps... adjust delay slider until audio syncs with the visual flash"
        captureService?.startCalibration()
    }

    private fun updateUI() {
        btnStartStop.text = if (isStreaming) "Stop Streaming" else "Start Streaming"
        btnCalibrate.isEnabled = isStreaming
        delaySlider.isEnabled = isStreaming
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
