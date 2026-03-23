package com.sonosbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.*
import kotlinx.coroutines.*

/**
 * Main activity for Sonos Bridge.
 *
 * Designed for D-pad navigation on Android TV boxes.
 * Flow: Discover Sonos (or enter IP manually) → Select speaker → Grant capture permission → Stream
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "SonosBridge"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val PREFS_NAME = "sonos_bridge_prefs"
        private const val PREF_LAST_SONOS_IP = "last_sonos_ip"
        private const val PREF_LAST_SONOS_NAME = "last_sonos_name"
        private const val PREF_VIDEO_DELAY = "video_delay"
    }

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var speakerInfo: TextView
    private lateinit var btnDiscover: Button
    private lateinit var btnManualIp: Button
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
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Bind UI elements
        statusText = findViewById(R.id.statusText)
        speakerInfo = findViewById(R.id.speakerInfo)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnManualIp = findViewById(R.id.btnManualIp)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        delaySlider = findViewById(R.id.delaySlider)
        delayLabel = findViewById(R.id.delayLabel)
        speakerList = findViewById(R.id.speakerList)

        // --- Discover Sonos speakers ---
        btnDiscover.setOnClickListener {
            discoverSpeakers()
        }

        // --- Manual IP entry ---
        btnManualIp.setOnClickListener {
            showManualIpDialog()
        }

        // --- Speaker selection ---
        speakerList.setOnItemClickListener { _, _, position, _ ->
            selectedSpeaker = discoveredSpeakers[position]
            speakerInfo.text = "Selected: ${selectedSpeaker?.name} (${selectedSpeaker?.ip})"
            saveSpeaker(selectedSpeaker!!)
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
        videoDelayMs = prefs.getLong(PREF_VIDEO_DELAY, 1500)
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
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putLong(PREF_VIDEO_DELAY, videoDelayMs).apply()
            }
        })

        // --- Calibration beep test ---
        btnCalibrate.setOnClickListener {
            runCalibration()
        }

        // Bind to capture service
        Intent(this, AudioCaptureService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        // Restore last used speaker if available
        restoreLastSpeaker()

        updateUI()
    }

    /**
     * Show a dialog for manually entering a Sonos speaker IP address.
     * Pre-fills with the last used IP if available.
     */
    private fun showManualIpDialog() {
        val lastIp = prefs.getString(PREF_LAST_SONOS_IP, "") ?: ""

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val label = TextView(this).apply {
            text = "Enter your Sonos speaker's IP address:"
            textSize = 16f
        }
        layout.addView(label)

        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.42"
            setText(lastIp)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = 20f
            isFocusable = true
            isFocusableInTouchMode = true
        }
        layout.addView(input)

        val helpText = TextView(this).apply {
            text = "Find this in the Sonos app:\nSettings > System > About My System"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        layout.addView(helpText)

        AlertDialog.Builder(this)
            .setTitle("Manual Sonos IP")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty() && isValidIp(ip)) {
                    connectManualIp(ip)
                } else {
                    statusText.text = "Invalid IP address"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()

        // Request focus on the input field for keyboard
        input.requestFocus()
    }

    /**
     * Validate a basic IPv4 address format.
     */
    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    /**
     * Connect to a manually entered Sonos IP.
     * Attempts to fetch the device name, falls back to "Sonos (IP)" if unreachable.
     */
    private fun connectManualIp(ip: String) {
        statusText.text = "Connecting to $ip..."

        scope.launch {
            val speaker = withContext(Dispatchers.IO) {
                val name = try {
                    SonosDiscovery.fetchSpeakerName(ip) ?: "Sonos ($ip)"
                } catch (_: Exception) {
                    "Sonos ($ip)"
                }
                SonosController.SonosSpeaker(name = name, ip = ip, port = 1400)
            }

            selectedSpeaker = speaker
            speakerInfo.text = "Selected: ${speaker.name} (${speaker.ip})"
            saveSpeaker(speaker)

            // Add to discovered list so it shows in the UI
            discoveredSpeakers = listOf(speaker)
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_1,
                listOf("${speaker.name} (${speaker.ip})")
            )
            speakerList.adapter = adapter

            statusText.text = "Connected to ${speaker.name}"
            btnStartStop.isEnabled = true
            btnStartStop.requestFocus()
        }
    }

    /**
     * Save the selected speaker to preferences for next launch.
     */
    private fun saveSpeaker(speaker: SonosController.SonosSpeaker) {
        prefs.edit()
            .putString(PREF_LAST_SONOS_IP, speaker.ip)
            .putString(PREF_LAST_SONOS_NAME, speaker.name)
            .apply()
    }

    /**
     * Restore the last used speaker on app launch.
     * Allows quick reconnection without re-discovering.
     */
    private fun restoreLastSpeaker() {
        val lastIp = prefs.getString(PREF_LAST_SONOS_IP, null)
        val lastName = prefs.getString(PREF_LAST_SONOS_NAME, null)

        if (lastIp != null && lastName != null) {
            val speaker = SonosController.SonosSpeaker(name = lastName, ip = lastIp, port = 1400)
            selectedSpeaker = speaker
            speakerInfo.text = "Last used: $lastName ($lastIp)"

            discoveredSpeakers = listOf(speaker)
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_1,
                listOf("$lastName ($lastIp)")
            )
            speakerList.adapter = adapter

            btnStartStop.isEnabled = true
            statusText.text = "Ready - last speaker restored. Press Start or re-discover."
        }
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
                    statusText.text = "No Sonos speakers found. Try Manual IP instead."
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
                statusText.text = "Discovery failed: ${e.message}. Try Manual IP."
                Log.e(TAG, "SSDP discovery failed", e)
            } finally {
                btnDiscover.isEnabled = true
            }
        }
    }

    /**
     * Request MediaProjection permission (system dialog).
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
        // Wait for the capture service to start the HTTP server first
        scope.launch(Dispatchers.IO) {
            try {
                // Give the service time to start the stream server
                Thread.sleep(2000)

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
