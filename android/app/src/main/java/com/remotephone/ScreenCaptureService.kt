package com.remotephone

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.remotephone.desktop.DesktopSession
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "remote_phone_capture"
        const val ACTION_START = "com.remotephone.START"
        const val ACTION_STOP = "com.remotephone.STOP"
        const val ACTION_APPROVE = "com.remotephone.APPROVE"
        const val ACTION_DENY = "com.remotephone.DENY"
        const val EXTRA_CLIENT = "client"
        const val REQUEST_CHANNEL_ID = "remote_phone_requests"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        /** ACTION_START: mirror the phone screen even when desktop mode is on (desktop mode failed). */
        const val EXTRA_FORCE_PHONE = "force_phone"
        const val WS_PORT = 8765

        // Frame type markers for the binary protocol
        const val FRAME_VIDEO_CONFIG: Byte = 0x00
        const val FRAME_VIDEO_KEY: Byte = 0x01
        const val FRAME_VIDEO_DELTA: Byte = 0x02
        const val FRAME_AUDIO_CONFIG: Byte = 0x10
        const val FRAME_AUDIO_DATA: Byte = 0x11

        private var instance: ScreenCaptureService? = null

        fun toggleAudio(enabled: Boolean) {
            instance?.setAudioEnabled(enabled)
        }

        /** Addresses of the clients currently watching, or null when not mirroring. */
        fun clients(): List<String>? = instance?.takeIf { it.isRunning }?.webSocketServer?.clientAddresses()

        /** Computers waiting for the owner's answer; empty when not mirroring. */
        fun pendingRequests(): List<MirrorWebSocketServer.PendingRequest> =
            instance?.takeIf { it.isRunning }?.webSocketServer?.pendingRequests() ?: emptyList()

        fun answer(id: String, allow: Boolean) {
            instance?.answer(id, allow)
        }

        /** Tells connected clients the phone's (new) name. */
        fun refreshInfo() {
            instance?.webSocketServer?.refreshInfo()
        }

        /** Re-reads the allowed networks (Settings changed) and listens on exactly those. */
        fun rebind() {
            instance?.let { s -> s.mainHandler.post { s.rebindServers() } }
        }

        /** Starts or stops the embedded web client server of a running mirroring session. */
        fun setWebClient(enabled: Boolean) {
            instance?.let { s -> s.mainHandler.post { s.updateWebClient(enabled) } }
        }

        /** Set by the activity while visible; called on the main thread when watchers or requests change. */
        @Volatile var onClientsChanged: ((List<String>) -> Unit)? = null

        /** Main thread, on every start/stop: an open main screen follows the Quick Settings tile. */
        @Volatile var onRunningChanged: (() -> Unit)? = null

        /** Mirroring, or about to (between the consent and the video being ready). */
        fun isActive(): Boolean = instance?.let { it.isRunning || it.isStarting } == true

        fun isStarting(): Boolean = instance?.isStarting == true

        /**
         * Desktop mode on Android 12+ with Shizuku ready: the picture comes from Shizuku's display
         * and the audio from its remote submix, so no screen capture consent is asked at all.
         */
        fun canStartWithoutConsent(ctx: Context): Boolean =
            Prefs.desktop(ctx) && Build.VERSION.SDK_INT >= 31 && DesktopSession.state() == DesktopSession.State.READY

        fun startWithoutConsent(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ScreenCaptureService::class.java).setAction(ACTION_START))
        }

        /** Desktop mode is streaming, so the phone's own screen can be dimmed or turned off. */
        fun phoneScreenControls(): Boolean = instance?.let { it.isRunning && it.desktop != null } == true
        fun phoneScreenOff(): Boolean = instance?.phoneScreenOff == true
        fun phoneBlackout(): Boolean = RemoteAccessibilityService.isBlackout()

        fun setPhoneScreenOff(off: Boolean) { instance?.applyPhoneScreenOff(off) }
        fun setPhoneBlackout(on: Boolean) { instance?.applyPhoneBlackout(on) }
    }

    private fun answer(id: String, allow: Boolean) {
        if (allow) webSocketServer?.approve(id) else webSocketServer?.deny(id)
        getSystemService(NotificationManager::class.java).cancel(requestNotificationId(id))
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Runs on the main thread whenever the watchers or requests change
    private val publishClients = Runnable {
        if (!isRunning) return@Runnable
        val clients = webSocketServer?.clientAddresses() ?: emptyList()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(clients))
        onClientsChanged?.invoke(clients)
        runningChanged()
    }

    /** Tell the tile (and an open main screen) that mirroring started, stopped or got watchers. */
    private fun runningChanged() {
        mainHandler.post {
            onRunningChanged?.invoke()
            MirrorTileService.listening?.invoke()
            MirrorTileService.refresh(this)
        }
    }

    // Video
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var videoThread: Thread? = null

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioEnabled = false
    private var audioAvailable = false
    private var savedMusicVolume = 0

    // Network
    private var webSocketServer: MirrorWebSocketServer? = null

    // Browser client served from the phone (optional)
    private var webClientServer: WebClientServer? = null

    // Desktop mode: a Shizuku-created display (DeX / Android desktop) instead of the phone screen
    private var desktop: DesktopSession? = null

    private var isRunning = false
    private var isStarting = false  // between Start and the video source being ready
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val forcePhone = intent.getBooleanExtra(EXTRA_FORCE_PHONE, false)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data != null && resultCode == Activity.RESULT_OK) {
                    startCapture(resultCode, data, forcePhone)
                } else if (!forcePhone && canStartWithoutConsent(this)) {
                    startCapture(Activity.RESULT_CANCELED, null, false)
                } else if (!isRunning && !isStarting) {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // Allow / Deny from the connection request notification
            ACTION_APPROVE, ACTION_DENY -> {
                intent.getStringExtra(EXTRA_CLIENT)?.let { answer(it, intent.action == ACTION_APPROVE) }
                if (!isRunning) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** [data] is null for desktop mode without consent (see [canStartWithoutConsent]). */
    private fun startCapture(resultCode: Int, data: Intent?, forcePhone: Boolean) {
        if (isRunning || isStarting) return
        isStarting = true
        shizukuAudio = false
        runningChanged()

        // Create notification channel and start foreground. Without a projection the service
        // may not claim the mediaProjection type (Android 14+ checks it), so it is a special use.
        createNotificationChannel()
        if (data == null && Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (data != null) {
            // MediaProjection: the phone screen, and the audio unless Shizuku provides it
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }, null)

            // Setup audio capture (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                audioAvailable = setupAudioCapture()
            }
        }

        if (!Prefs.desktop(this) || forcePhone) {
            if (mediaProjection == null) {  // nothing to mirror without the consent
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            startPhoneVideo()
            finishStart()
            return
        }
        // Desktop mode: the display comes from Shizuku's shell process, which starts asynchronously.
        // Any failure falls back to mirroring the phone screen, with the reason shown on the phone.
        val session = DesktopSession(this)
        session.bind(5_000) { bound ->
            if (!isStarting) {  // stopped while binding
                session.release()
                return@bind
            }
            if (bound && startDesktopVideo(session)) {
                desktop = session
                if (mediaProjection == null) {
                    // Audio from Shizuku's remote submix, checked once so clients see the real state
                    val error = session.startAudio()
                    if (error == null) session.stopAudio() else Log.w(TAG, "Shizuku audio unavailable: $error")
                    shizukuAudio = error == null
                    audioAvailable = shizukuAudio
                }
                RemoteAccessibilityService.onBlackoutChanged = { phoneScreenChanged() }
            } else if (mediaProjection == null) {
                // No consent was asked, so there is nothing to fall back to here: ask for it now
                val reason = session.lastError ?: "unknown error"
                Log.w(TAG, "Desktop mode unavailable: $reason")
                Toast.makeText(this, "Desktop mode unavailable ($reason). Mirroring the phone screen instead.", Toast.LENGTH_LONG).show()
                session.release()
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                try {
                    startActivity(Intent(this, MirrorStartActivity::class.java)
                        .putExtra(EXTRA_FORCE_PHONE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    Log.w(TAG, "could not ask for screen capture", t)
                }
                return@bind
            } else {
                val reason = session.lastError ?: "unknown error"
                Log.w(TAG, "Desktop mode unavailable: $reason")
                Toast.makeText(this, "Desktop mode unavailable ($reason). Mirroring the phone screen.", Toast.LENGTH_LONG).show()
                session.release()
                startPhoneVideo()
            }
            finishStart()
        }
    }

    /** Mirror the phone's own screen through MediaProjection. */
    private fun startPhoneVideo() {
        readScreenSize()

        val encoder = createEncoder()
        val surface = encoder.createInputSurface()
        encoder.start()
        videoEncoder = encoder
        inputSurface = surface

        // Create virtual display targeting the encoder surface
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "RemotePhone",
            screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null, null
        )
    }

    /** A separate desktop display at the configured size, rendered straight into the encoder. */
    private fun startDesktopVideo(session: DesktopSession): Boolean {
        val (width, height, dpi) = Prefs.desktopSize(this)
        screenWidth = width and (-2)
        screenHeight = height and (-2)
        density = dpi
        val encoder = try { createEncoder() } catch (e: Exception) { Log.e(TAG, "Desktop encoder failed", e); return false }
        val surface = encoder.createInputSurface()
        encoder.start()
        if (!session.createDisplay(screenWidth, screenHeight, density, surface)) {
            try { encoder.stop(); encoder.release(); surface.release() } catch (_: Exception) {}
            return false
        }
        videoEncoder = encoder
        inputSurface = surface
        Log.i(TAG, "Desktop display ${session.displayId}: ${screenWidth}x${screenHeight} @ ${density}dpi")
        return true
    }

    /** Video source ready: open the server and start streaming. */
    private fun finishStart() {
        // Start WebSocket server
        webSocketServer = MirrorWebSocketServer(
            port = WS_PORT,
            context = this,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            audioAvailable = audioAvailable,
            onControlCommand = { cmd ->
                // Desktop mode injects on its own display; what it doesn't handle goes to accessibility
                if (!handlePhoneScreen(cmd) && desktop?.handle(cmd) != true) RemoteAccessibilityService.handleCommand(cmd)
            },
            onClientsChanged = { mainHandler.post(publishClients) },
            onApprovalRequest = { id, label -> requestApproval(id, label) },
            desktop = desktop != null
        )
        // Everything, video included, travels inside TLS under the phone's own certificate
        webSocketServer!!.setWebSocketFactory(DefaultSSLWebSocketServerFactory(Tls.serverContext()))
        // A quick stop and start must not fail on the previous socket still closing
        webSocketServer!!.isReuseAddr = true
        webSocketServer!!.start(NetworkAddresses.bindAddresses(this))
        watchNetworks(true)

        isStarting = false
        isRunning = true
        updateWebClient(Prefs.webClient(this))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())

        // Start reading encoded video frames
        val encoder = videoEncoder!!
        videoThread = Thread({ readVideoEncoder(encoder) }, "VideoEncoderThread").apply { start() }

        runningChanged()

        Log.i(TAG, "Screen capture started: ${screenWidth}x${screenHeight} on port $WS_PORT" + if (desktop != null) " (desktop)" else "")
    }

    /**
     * Real screen size including system bars, even-aligned as some hardware encoders require.
     * Returns true when the size changed since the last read (rotation, fold).
     */
    private fun readScreenSize(): Boolean {
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            density = resources.displayMetrics.densityDpi
        } else {
            val realMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(realMetrics)
            screenWidth = realMetrics.widthPixels
            screenHeight = realMetrics.heightPixels
            density = realMetrics.densityDpi
        }
        Log.i(TAG, "Real screen dimensions: ${screenWidth}x${screenHeight} @ ${density}dpi")
        screenWidth = screenWidth and (-2)
        screenHeight = screenHeight and (-2)
        return screenWidth != oldWidth || screenHeight != oldHeight
    }

    /**
     * H.264 encoder at the current screen size, tuned for low-latency streaming.
     * Some hardware encoders (common on LineageOS/custom ROMs) reject the profile/level or
     * optional keys with IllegalArgumentException; those fall back to the base format.
     */
    private fun createEncoder(): MediaCodec {
        val videoFormat = baseVideoFormat().apply {
            // Reduce encoder output buffering for lower latency
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // When screen is static, only repeat frame every 100ms instead of 33ms
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // microseconds
            // Main profile: better compression than Baseline, no B-frames so still low latency
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Encoder config failed with optimized format, retrying with baseline", e)
            encoder.reset()
            encoder.configure(baseVideoFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return encoder
    }

    // Services receive the device configuration, so this fires when another app rotates the
    // screen even though MainActivity is portrait-locked. Locale, font and theme changes land
    // here too, and a 180 degree flip keeps the size, so only a real size change restarts video.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The desktop display keeps its own size whatever the phone does
        if (isRunning && desktop == null && readScreenSize()) restartVideo()
    }

    /**
     * Swap in an encoder at the new screen size. The virtual display is resized, not recreated:
     * Android 14 allows a single createVirtualDisplay per MediaProjection.
     */
    private fun restartVideo() {
        try {
            val old = videoEncoder
            val oldSurface = inputSurface
            val oldThread = videoThread
            val encoder = createEncoder()
            val surface = encoder.createInputSurface()
            encoder.start()
            videoEncoder = encoder  // the old reader loop exits on this swap
            inputSurface = surface
            oldThread?.join(500)
            // Resize before attaching, so the new surface only ever sees the new projection
            virtualDisplay?.resize(screenWidth, screenHeight, density)
            virtualDisplay?.setSurface(surface)
            try {
                old?.stop()
                old?.release()
                oldSurface?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing old encoder", e)
            }
            // Clients learn the new size before the first frame at that size arrives
            webSocketServer?.updateScreenSize(screenWidth, screenHeight)
            videoThread = Thread({ readVideoEncoder(encoder) }, "VideoEncoderThread").apply { start() }
            Log.i(TAG, "Video restarted at ${screenWidth}x${screenHeight} after rotation")
        } catch (e: Exception) {
            Log.e(TAG, "Video restart after rotation failed", e)
        }
    }

    /** The minimal H.264 format every encoder accepts; optional low-latency keys are layered on top. */
    private fun baseVideoFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, calculateBitrate(screenWidth, screenHeight))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // keyframe every 1s for faster error recovery
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

    private fun calculateBitrate(width: Int, height: Int): Int {
        // Adaptive bitrate: ~8 bits per pixel for high quality over WiFi
        return (width * height * 8).coerceIn(4_000_000, 40_000_000)
    }

    private fun readVideoEncoder(encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        // Exits when capture stops or a rotation swaps in a new encoder
        while (isRunning && videoEncoder === encoder) {
            try {
                val index = encoder.dequeueOutputBuffer(bufferInfo, 1_000)  // 1ms timeout for low latency
                if (index >= 0) {
                    val buffer = encoder.getOutputBuffer(index) ?: continue

                    if (bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        val data = ByteArray(bufferInfo.size)
                        buffer.get(data, 0, bufferInfo.size)

                        val timestamp = (bufferInfo.presentationTimeUs / 1000).toInt()

                        val frameType = when {
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> FRAME_VIDEO_CONFIG
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> FRAME_VIDEO_KEY
                            else -> FRAME_VIDEO_DELTA
                        }

                        sendFrame(frameType, timestamp, data)
                    }

                    encoder.releaseOutputBuffer(index, false)
                }
            } catch (e: Exception) {
                if (isRunning && videoEncoder === encoder) Log.e(TAG, "Video encoder read error", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioCapture(): Boolean {
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(16384)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            Log.i(TAG, "Audio capture setup successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture setup failed", e)
            false
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        if (!audioAvailable) return
        if (shizukuAudio) { setShizukuAudio(enabled); return }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (enabled && !audioEnabled) {
            audioEnabled = true
            // Mute phone speakers so audio only plays on the Linux client
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioRecord?.startRecording()
            audioThread = Thread({ readAudioCapture() }, "AudioThread").apply { start() }
            Log.i(TAG, "Audio streaming enabled — phone speakers muted")
        } else if (!enabled && audioEnabled) {
            audioEnabled = false
            try { audioRecord?.stop() } catch (_: Exception) {}
            // Restore phone speaker volume
            if (savedMusicVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            }
            Log.i(TAG, "Audio streaming disabled — phone speakers restored")
        }
    }

    // Desktop mode without MediaProjection: Shizuku's process records the remote submix, which
    // takes the audio away from the phone speaker by itself, so the volume is left alone.
    @Volatile private var shizukuAudio = false

    private fun setShizukuAudio(enabled: Boolean) {
        val session = desktop ?: return
        if (enabled && !audioEnabled) {
            audioEnabled = true
            audioThread = Thread({
                val error = session.startAudio()
                if (error != null) {
                    Log.e(TAG, "Shizuku audio failed: $error")
                    audioEnabled = false
                    return@Thread
                }
                Log.i(TAG, "Audio streaming enabled (Shizuku)")
                while (audioEnabled && isRunning) {
                    val bytes = session.readAudio(16384)
                    if (bytes.isEmpty()) { try { Thread.sleep(20) } catch (_: InterruptedException) { break }; continue }
                    sendFrame(FRAME_AUDIO_DATA, (System.nanoTime() / 1_000_000).toInt(), bytes)
                }
            }, "AudioThread").apply { start() }
        } else if (!enabled && audioEnabled) {
            audioEnabled = false
            Thread { session.stopAudio() }.start()
            Log.i(TAG, "Audio streaming disabled (Shizuku)")
        }
    }

    // ---- The phone's own screen while desktop mode streams ----

    @Volatile private var phoneScreenOff = false
    private var screenLock: PowerManager.WakeLock? = null
    private var screenOffReceiver: BroadcastReceiver? = null

    /** {"type":"phone_screen","off":true} and {"type":"phone_blackout","on":true} from clients. */
    private fun handlePhoneScreen(json: String): Boolean {
        if (!json.contains("\"phone_")) return false
        val cmd = try { org.json.JSONObject(json) } catch (_: Exception) { return false }
        when (cmd.optString("type")) {
            "phone_screen" -> applyPhoneScreenOff(cmd.optBoolean("off"))
            "phone_blackout" -> applyPhoneBlackout(cmd.optBoolean("on"))
            else -> return false
        }
        return true
    }

    private fun applyPhoneScreenOff(off: Boolean) {
        val session = desktop ?: return
        Thread {
            val error = session.setScreenPower(!off)
            mainHandler.post {
                if (error != null) {
                    Log.w(TAG, "phone screen power failed: $error")
                    Toast.makeText(this, "Could not turn the phone screen " + (if (off) "off" else "on") + ". Try the black screen instead.", Toast.LENGTH_LONG).show()
                } else {
                    setPhoneScreenOffState(off)
                }
            }
        }.start()
    }

    /**
     * While the panel is off the phone must stay awake, or the screen timeout would put it (and
     * the desktop) to sleep. The power button still sleeps it; then the panel is back on by itself.
     */
    private fun setPhoneScreenOffState(off: Boolean) {
        phoneScreenOff = off
        screenLock?.let { if (it.isHeld) it.release() }
        screenLock = null
        screenOffReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenOffReceiver = null
        if (off) {
            @Suppress("DEPRECATION")
            screenLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.FULL_WAKE_LOCK, "RemotePhone:PhoneScreenOff").apply { acquire() }
            screenOffReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { setPhoneScreenOffState(false) }
            }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        }
        phoneScreenChanged()
    }

    private fun applyPhoneBlackout(on: Boolean) {
        if (desktop == null && on) return
        if (!RemoteAccessibilityService.setBlackout(on)) {
            Toast.makeText(this, "The black screen needs RemotePhone's accessibility service", Toast.LENGTH_LONG).show()
            return
        }
        mainHandler.postDelayed({ phoneScreenChanged() }, 100)  // after the overlay was added/removed
    }

    private fun phoneScreenChanged() {
        webSocketServer?.refreshInfo()
        runningChanged()
    }

    private fun readAudioCapture() {
        val buffer = ByteArray(16384) // ~93ms at 44100Hz stereo 16-bit

        while (audioEnabled && isRunning) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    val timestamp = (System.nanoTime() / 1_000_000).toInt()
                    sendFrame(FRAME_AUDIO_DATA, timestamp, buffer.copyOf(bytesRead))
                }
            } catch (e: Exception) {
                if (audioEnabled) Log.e(TAG, "Audio capture error", e)
            }
        }
    }

    private fun sendFrame(type: Byte, timestamp: Int, data: ByteArray) {
        // 9-byte big-endian header: [type(1)] [timestamp(4)] [size(4)], then the payload
        val frame = ByteBuffer.allocate(9 + data.size)
            .put(type).putInt(timestamp).putInt(data.size).put(data)
            .array()
        webSocketServer?.broadcastFrame(frame)
    }

    private fun stopCapture() {
        if (!isRunning && !isStarting) return
        isRunning = false
        isStarting = false

        // Restore phone volume if audio was streaming
        try { setAudioEnabled(false) } catch (_: Exception) {}

        // Stop threads
        videoThread?.interrupt()
        audioThread?.interrupt()

        updateWebClient(false)
        watchNetworks(false)

        // Give the phone its screen back (the shell side powers the panel on when the display goes)
        if (phoneScreenOff) setPhoneScreenOffState(false)
        RemoteAccessibilityService.setBlackout(false)
        RemoteAccessibilityService.onBlackoutChanged = null
        shizukuAudio = false

        // Remove the desktop display (and its shell process) before its surface goes away
        desktop?.release()
        desktop = null

        // Release video encoder
        try {
            virtualDisplay?.release()
            videoEncoder?.stop()
            videoEncoder?.release()
            inputSurface?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video encoder", e)
        }

        // Release audio
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio", e)
        }

        // Stop MediaProjection
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping projection", e)
        }

        // Stop WebSocket server on background thread to avoid ANR
        val server = webSocketServer
        if (server != null) {
            Thread {
                try {
                    server.stop(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping WebSocket server", e)
                }
            }.start()
        }

        virtualDisplay = null
        videoEncoder = null
        inputSurface = null
        audioRecord = null
        mediaProjection = null
        webSocketServer = null

        runningChanged()

        Log.i(TAG, "Screen capture stopped")
    }

    // Networks come and go (WiFi off, VPN on): listen on the allowed addresses of the moment
    private val rebindRunnable = Runnable { rebindServers() }
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { queueRebind() }
        override fun onLost(network: android.net.Network) { queueRebind() }
        override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) { queueRebind() }
    }

    private fun queueRebind() {
        mainHandler.removeCallbacks(rebindRunnable)
        mainHandler.postDelayed(rebindRunnable, 500)
    }

    private fun watchNetworks(on: Boolean) {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        try {
            if (on) {
                val request = android.net.NetworkRequest.Builder()
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
                cm.registerNetworkCallback(request, networkCallback)
            } else {
                cm.unregisterNetworkCallback(networkCallback)
                mainHandler.removeCallbacks(rebindRunnable)
            }
        } catch (_: Exception) {}
    }

    private fun rebindServers() {
        if (!isRunning) return
        val ws = webSocketServer
        val web = webClientServer
        Thread {
            val addresses = NetworkAddresses.bindAddresses(this)
            ws?.rebind(addresses)
            web?.rebind(addresses)
        }.start()
    }

    private fun updateWebClient(enabled: Boolean) {
        if (enabled && isRunning && webClientServer == null) {
            val server = WebClientServer(this)
            // Binding opens a socket: keep it off the main thread
            Thread {
                try {
                    server.start(NetworkAddresses.bindAddresses(this))
                    webClientServer = server
                } catch (e: Exception) {
                    Log.e(TAG, "Web client server failed to start", e)
                    mainHandler.post { Toast.makeText(this, "Web client could not start: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        } else if (!enabled || !isRunning) {
            val server = webClientServer ?: return
            webClientServer = null
            Thread { server.stop() }.start()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirroring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when screen mirroring is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            // High importance so the request pops up over whatever the phone is showing
            manager.createNotificationChannel(NotificationChannel(
                REQUEST_CHANNEL_ID, "Connection requests", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Asks before a computer may view and control this phone" })
        }
    }

    private fun requestNotificationId(id: String) = 2000 + (id.hashCode() and 0xFFFF)

    /** Ask the owner whether the computer [label] may watch and control the phone. Unanswered, the server denies after a minute. */
    private fun requestApproval(id: String, label: String) {
        fun answer(action: String) = PendingIntent.getService(
            this, id.hashCode(),
            Intent(this, ScreenCaptureService::class.java).setAction(action).putExtra(EXTRA_CLIENT, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setContentTitle("Connection request")
            .setContentText("Allow $label to view and control this phone?")
            .setStyle(NotificationCompat.BigTextStyle())
            .setSmallIcon(R.drawable.ic_cast)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(0, "Allow", answer(ACTION_APPROVE))
            .addAction(0, "Deny", answer(ACTION_DENY))
            .build()
        getSystemService(NotificationManager::class.java).notify(requestNotificationId(id), notification)
        Log.i(TAG, "Asking to allow $label")
    }

    private fun buildNotification(clients: List<String> = emptyList()): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (desktop != null) "RemotePhone · desktop" else "RemotePhone")
            .setContentText(
                if (clients.isEmpty()) "Mirroring, no client connected"
                else "Mirroring to ${clients.joinToString(", ")}"
            )
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
