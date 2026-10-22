package com.remotephone.desktop

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.lang.reflect.Method

/**
 * Runs inside Shizuku's process (shell uid), started with Shizuku.bindUserService.
 *
 * Shell may create a public, trusted virtual display with system decorations. On One UI 8+
 * (Android 16) Samsung DeX starts on such a display, elsewhere the Android desktop / secondary
 * home does. The app hands over its encoder's input Surface, so the display renders straight
 * into the H.264 encoder, and forwards mouse/keyboard input, which is injected on that display.
 * Same approach as scrcpy's --new-display.
 *
 * It also captures the device's audio output (REMOTE_SUBMIX, allowed for shell) so desktop mode
 * needs no MediaProjection consent at all, and can power the phone's own panel off while the
 * desktop display keeps running (scrcpy's --turn-screen-off).
 */
@Keep
class DesktopUserService() : Binder() {

    private var baseContext: Context? = null

    /** Shizuku v13+ passes a Context when such a constructor exists. */
    @Keep
    constructor(context: Context) : this() {
        baseContext = context
    }

    init {
        attachInterface(null, DesktopProtocol.DESCRIPTOR)
    }

    private var display: VirtualDisplay? = null
    @Volatile private var displayId = -1
    private var mouseDownTime = 0L
    private var buttons = 0
    private var keyDownTime = 0L

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DesktopProtocol.DESTROY) {
            release()
            stopAudio()
            if (screenOff) try { setScreenPower(true) } catch (_: Throwable) {}
            System.exit(0)
            return true
        }
        if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DesktopProtocol.DESCRIPTOR)
        when (code) {
            DesktopProtocol.CREATE_DISPLAY -> {
                val width = data.readInt()
                val height = data.readInt()
                val dpi = data.readInt()
                val surface = Surface.CREATOR.createFromParcel(data)
                var error: String? = null
                val id = try {
                    createDisplay(width, height, dpi, surface)
                } catch (t: Throwable) {
                    Log.e(TAG, "createVirtualDisplay failed", t)
                    error = t.toString()
                    -1
                }
                reply?.writeNoException()
                reply?.writeInt(id)
                reply?.writeString(error)
            }
            DesktopProtocol.RELEASE_DISPLAY -> {
                release()
                stopAudio()
                if (screenOff) try { setScreenPower(true) } catch (t: Throwable) { Log.w(TAG, "screen restore failed", t) }
                reply?.writeNoException()
            }
            DesktopProtocol.MOUSE -> safely {
                mouse(data.readInt(), data.readFloat(), data.readFloat(), data.readInt(), data.readInt())
            }
            DesktopProtocol.SCROLL -> safely {
                val x = data.readFloat()
                val y = data.readFloat()
                val h = data.readFloat()
                val v = data.readFloat()
                motion(MotionEvent.ACTION_SCROLL, x, y, buttons, h = h, v = v)
            }
            DesktopProtocol.KEY -> safely {
                key(data.readInt(), data.readInt(), data.readInt(), data.readInt())
            }
            DesktopProtocol.TEXT -> {
                val ok = try { text(data.readString() ?: "") } catch (t: Throwable) { Log.w(TAG, "text failed", t); false }
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
            }
            DesktopProtocol.AUDIO_START -> {
                val error = try { startAudio(); null } catch (t: Throwable) { Log.e(TAG, "audio capture failed", t); stopAudio(); t.toString() }
                reply?.writeNoException()
                reply?.writeString(error)
            }
            DesktopProtocol.AUDIO_READ -> {
                val max = data.readInt().coerceIn(1024, 65536)
                val record = audio
                val bytes = if (record == null) ByteArray(0) else {
                    val buf = ByteArray(max)
                    val n = try { record.read(buf, 0, max) } catch (t: Throwable) { -1 }
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                }
                reply?.writeNoException()
                reply?.writeByteArray(bytes)
            }
            DesktopProtocol.AUDIO_STOP -> {
                stopAudio()
                reply?.writeNoException()
            }
            DesktopProtocol.SCREEN_POWER -> {
                val on = data.readInt() != 0
                val error = try { setScreenPower(on); null } catch (t: Throwable) { Log.e(TAG, "screen power failed", t); t.toString() }
                reply?.writeNoException()
                reply?.writeString(error)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    // ------------------------------------------------------------------------------------------
    // Audio: the whole device output through the remote submix, as scrcpy's default audio source
    // ------------------------------------------------------------------------------------------

    @Volatile private var audio: AudioRecord? = null

    @Synchronized
    private fun startAudio() {
        if (audio != null) return
        if (Build.VERSION.SDK_INT < 31) throw UnsupportedOperationException("Shizuku audio needs Android 12+")
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val size = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(16384) * 4
        // The record must be attributed to the shell package this process runs as
        val record = AudioRecord.Builder()
            .setContext(ShellContext(baseContext ?: systemContext()))
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(format)
            .setBufferSizeInBytes(size)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord not initialized")
        }
        record.startRecording()
        audio = record
        Log.i(TAG, "Audio capture started (remote submix)")
    }

    @Synchronized
    private fun stopAudio() {
        val record = audio ?: return
        audio = null
        try { record.stop() } catch (_: Throwable) {}
        record.release()
        Log.i(TAG, "Audio capture stopped")
    }

    // ------------------------------------------------------------------------------------------
    // Phone panel power: off while the desktop display keeps running (scrcpy --turn-screen-off)
    // ------------------------------------------------------------------------------------------

    @Volatile private var screenOff = false

    private fun setScreenPower(on: Boolean) {
        // Android 15+: a public-ish entry point in the display manager, by display id
        if (Build.VERSION.SDK_INT >= 35 && requestDisplayPower(0, on)) { screenOff = !on; return }
        // Older: set the power mode of every physical display through SurfaceControl
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val setMode = surfaceControl.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        val tokens = physicalDisplayTokens(surfaceControl)
        if (tokens.isEmpty()) throw IllegalStateException("no physical display found")
        for (token in tokens) setMode.invoke(null, token, mode)
        screenOff = !on
    }

    private fun requestDisplayPower(displayId: Int, on: Boolean): Boolean {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null) ?: return false
        val targets = mutableListOf<Any>(global)
        try {
            val f = global.javaClass.getDeclaredField("mDm")
            f.isAccessible = true
            f.get(global)?.let { targets.add(it) }
        } catch (_: Throwable) {}
        for (target in targets) {
            try {
                val m = target.javaClass.getMethod("requestDisplayPower", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                val result = m.invoke(target, displayId, on)
                Log.i(TAG, "requestDisplayPower($displayId, $on) on ${target.javaClass.simpleName} -> $result")
                return result != false
            } catch (_: NoSuchMethodException) {
            }
        }
        return false
    }

    private fun physicalDisplayTokens(surfaceControl: Class<*>): List<IBinder> {
        // Android 14 moved these to com.android.server.display.DisplayControl, inside services.jar
        val holder: Class<*> = if (Build.VERSION.SDK_INT >= 34) displayControl() else surfaceControl
        return try {
            val ids = holder.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val getToken = holder.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            ids.toList().mapNotNull { getToken.invoke(null, it) as IBinder? }
        } catch (_: NoSuchMethodException) {
            // Android 9 and older: a single built-in display
            listOfNotNull(surfaceControl.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType).invoke(null, 0) as IBinder?)
        }
    }

    private fun displayControl(): Class<*> {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod("createClassLoader", String::class.java, String::class.java, String::class.java,
            ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
        val loader = create.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
        val cls = loader.loadClass("com.android.server.display.DisplayControl")
        val loadLibrary = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary.isAccessible = true
        loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers")
        return cls
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "input injection failed", t) }
    }

    // ------------------------------------------------------------------------------------------
    // Display
    // ------------------------------------------------------------------------------------------

    private fun createDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        release()
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or
            FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
        }
        // The DisplayManager must carry the shell package, or the service rejects the
        // package/uid mismatch; getSystemService would hand back one bound to our app package.
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        val dm = ctor.newInstance(ShellContext(baseContext ?: systemContext()))
        val vd = dm.createVirtualDisplay("RemotePhone desktop", width, height, dpi, surface, flags)
            ?: throw IllegalStateException("createVirtualDisplay returned null")
        display = vd
        displayId = vd.display.displayId
        Log.i(TAG, "Desktop display ${width}x${height}/${dpi} id=$displayId flags=0x${Integer.toHexString(flags)}")
        return displayId
    }

    private fun release() {
        display?.let {
            try { it.release() } catch (t: Throwable) { Log.w(TAG, "release failed", t) }
        }
        display = null
        displayId = -1
        buttons = 0
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    private val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_MOUSE
    })
    private val pointerCoords = arrayOf(MotionEvent.PointerCoords())

    private fun mouse(action: Int, x: Float, y: Float, newButtons: Int, actionButton: Int) {
        when (action) {
            DesktopProtocol.MOUSE_DOWN -> {
                if (buttons == 0) {
                    mouseDownTime = SystemClock.uptimeMillis()
                    buttons = newButtons
                    motion(MotionEvent.ACTION_DOWN, x, y, buttons)
                } else {
                    buttons = newButtons
                }
                motion(MotionEvent.ACTION_BUTTON_PRESS, x, y, buttons, actionButton)
            }
            DesktopProtocol.MOUSE_UP -> {
                if (buttons == 0) return
                buttons = newButtons
                motion(MotionEvent.ACTION_BUTTON_RELEASE, x, y, buttons, actionButton)
                if (buttons == 0) motion(MotionEvent.ACTION_UP, x, y, 0)
            }
            DesktopProtocol.MOUSE_MOVE -> {
                if (buttons != 0) motion(MotionEvent.ACTION_MOVE, x, y, buttons)
                else motion(MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
            }
        }
    }

    private fun motion(action: Int, x: Float, y: Float, buttonState: Int, actionButton: Int = 0, h: Float = 0f, v: Float = 0f) {
        if (displayId < 0) return
        val now = SystemClock.uptimeMillis()
        val coords = pointerCoords[0]
        coords.clear()
        coords.x = x
        coords.y = y
        coords.pressure = if (buttonState != 0 && action != MotionEvent.ACTION_UP) 1f else 0f
        if (action == MotionEvent.ACTION_SCROLL) {
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        val gesture = action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_SCROLL
        val event = MotionEvent.obtain(
            if (gesture) mouseDownTime else now, now, action, 1, pointerProperties, pointerCoords,
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        try {
            if (actionButton != 0) {
                try { setActionButton.invoke(event, actionButton) } catch (_: Throwable) {}
            }
            inject(event)
        } finally {
            event.recycle()
        }
    }

    private fun key(action: Int, keyCode: Int, metaState: Int, repeat: Int) {
        if (displayId < 0) return
        val now = SystemClock.uptimeMillis()
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) keyDownTime = now
        inject(KeyEvent(keyDownTime, now, action, keyCode, repeat, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }

    /** Types [text] with the virtual keyboard map; false when a character has no key (e.g. "ñ"). */
    private fun text(text: String): Boolean {
        if (displayId < 0 || text.isEmpty()) return false
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return false
        for (event in events) inject(event)
        return true
    }

    private fun inject(event: InputEvent): Boolean {
        setDisplayId.invoke(event, displayId)
        return injectMethod.invoke(inputManager, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */) as Boolean
    }

    private val inputManager: Any by lazy {
        try {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (t: Throwable) {
            InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)!!
        }
    }
    private val injectMethod: Method by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId: Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }
    private val setActionButton: Method by lazy {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    }

    /** A context whose package matches the shell uid this process runs as. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        @RequiresApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    companion object {
        private const val TAG = "RemotePhoneDesktop"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        // Hidden DisplayManager flags, same values scrcpy uses
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
