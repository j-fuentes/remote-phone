package com.remotephone.desktop

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.remotephone.RemoteAccessibilityService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * App side of desktop mode: binds [DesktopUserService] through Shizuku, asks it for a desktop
 * display that renders into the encoder surface, and turns client commands into mouse and
 * keyboard events on that display.
 *
 * Commands from clients that only know the phone protocol (tap, swipe, scroll, key...) are
 * translated too, so the desktop client works unchanged; the web client sends real mouse and
 * keyboard events (mouse, wheel, keycode).
 */
class DesktopSession(private val context: Context) {

    enum class State { UNSUPPORTED, NOT_RUNNING, NO_PERMISSION, READY }

    companion object {
        private const val TAG = "DesktopSession"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 7101

        /** Desktop mode needs system decorations on secondary displays (Android 10+) and a running, authorized Shizuku. */
        fun state(): State {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return State.UNSUPPORTED
            return try {
                when {
                    !Shizuku.pingBinder() -> State.NOT_RUNNING
                    Shizuku.isPreV11() -> State.UNSUPPORTED
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
                    else -> State.NO_PERMISSION
                }
            } catch (t: Throwable) {
                State.NOT_RUNNING
            }
        }

        private const val BUTTON_PRIMARY = 1
        private const val META_CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "DesktopInput") }

    @Volatile private var binder: IBinder? = null
    @Volatile var displayId = -1
        private set
    var lastError: String? = null
        private set

    private var pendingBind: ((Boolean) -> Unit)? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, DesktopUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("desktop")
            .debuggable(false)
            .version(DesktopProtocol.VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || !service.pingBinder()) return
            binder = service
            pendingBind?.let { pendingBind = null; it(true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            displayId = -1
            Log.w(TAG, "Desktop service disconnected")
        }
    }

    /** Starts the shell-side service; [done] runs on the main thread with false on timeout or when Shizuku is unusable. */
    fun bind(timeoutMs: Long, done: (Boolean) -> Unit) {
        when (state()) {
            State.READY -> Unit
            State.NOT_RUNNING -> { lastError = "Shizuku is not running"; done(false); return }
            State.NO_PERMISSION -> { lastError = "RemotePhone has no Shizuku permission"; done(false); return }
            State.UNSUPPORTED -> { lastError = "Needs Android 10+ and Shizuku 11+"; done(false); return }
        }
        pendingBind = done
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
            lastError = "Could not start the Shizuku service: ${t.message}"
            pendingBind = null
            done(false)
            return
        }
        main.postDelayed({
            pendingBind?.let {
                pendingBind = null
                lastError = "The Shizuku service did not start in time"
                it(false)
            }
        }, timeoutMs)
    }

    /** Creates the desktop display rendering into [surface]; false (see [lastError]) when the system refuses. */
    fun createDisplay(width: Int, height: Int, dpi: Int, surface: Surface): Boolean {
        val b = binder ?: return false.also { lastError = "Desktop service not connected" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DesktopProtocol.DESCRIPTOR)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(dpi)
            surface.writeToParcel(data, 0)
            b.transact(DesktopProtocol.CREATE_DISPLAY, data, reply, 0)
            reply.readException()
            displayId = reply.readInt()
            reply.readString()?.let { lastError = it }
            displayId >= 0
        } catch (t: Throwable) {
            Log.e(TAG, "createDisplay failed", t)
            lastError = t.toString()
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Removes the display and stops the shell-side process. */
    fun release() {
        worker.shutdownNow()
        pendingBind = null
        binder?.let { b ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DesktopProtocol.DESCRIPTOR)
                b.transact(DesktopProtocol.RELEASE_DISPLAY, data, reply, 0)
            } catch (_: Throwable) {
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) {}
        binder = null
        displayId = -1
    }

    // ------------------------------------------------------------------------------------------
    // Audio and phone panel (shell side, no MediaProjection)
    // ------------------------------------------------------------------------------------------

    /** Starts capturing the device audio output; null on success, else the reason. */
    fun startAudio(): String? = call(DesktopProtocol.AUDIO_START, {}) { it.readString() }.let { (ok, err) -> if (ok) err else lastError }

    /** Blocks until audio is available; empty when capture stopped or the service went away. */
    fun readAudio(max: Int): ByteArray = call(DesktopProtocol.AUDIO_READ, { it.writeInt(max) }) { it.createByteArray() }.second ?: ByteArray(0)

    fun stopAudio() { call(DesktopProtocol.AUDIO_STOP, {}) { } }

    /** Powers the phone's own panel on or off, the desktop display keeps running; null on success. */
    fun setScreenPower(on: Boolean): String? =
        call(DesktopProtocol.SCREEN_POWER, { it.writeInt(if (on) 1 else 0) }) { it.readString() }.let { (ok, err) -> if (ok) err else lastError }

    /** A synchronous transaction: (true, result) when it went through, (false, null) with [lastError] set when not. */
    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T?): Pair<Boolean, T?> {
        val b = binder ?: return (false to null).also { lastError = "Desktop service not connected" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DesktopProtocol.DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            true to read(reply)
        } catch (t: Throwable) {
            Log.w(TAG, "transaction $code failed", t)
            lastError = t.toString()
            false to null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Client commands
    // ------------------------------------------------------------------------------------------

    /** Handles a client command on the desktop display; false leaves it to the accessibility service. */
    fun handle(json: String): Boolean {
        if (binder == null || displayId < 0) return false
        val cmd = try { JSONObject(json) } catch (_: Exception) { return false }
        val run: Runnable = when (cmd.optString("type")) {
            // Real pointer and keyboard events (web client in desktop mode)
            "mouse" -> {
                val action = when (cmd.optString("action")) {
                    "down" -> DesktopProtocol.MOUSE_DOWN
                    "up" -> DesktopProtocol.MOUSE_UP
                    else -> DesktopProtocol.MOUSE_MOVE
                }
                val x = cmd.optDouble("x").toFloat()
                val y = cmd.optDouble("y").toFloat()
                val buttons = cmd.optInt("buttons")
                val button = cmd.optInt("button")
                Runnable { mouse(action, x, y, buttons, button) }
            }
            "wheel" -> {
                val x = cmd.optDouble("x").toFloat()
                val y = cmd.optDouble("y").toFloat()
                val h = cmd.optDouble("h", 0.0).toFloat()
                val v = cmd.optDouble("v", 0.0).toFloat()
                Runnable { scroll(x, y, h, v) }
            }
            "keycode" -> {
                val action = if (cmd.optString("action") == "up") KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
                val code = cmd.optInt("keycode")
                val meta = cmd.optInt("meta")
                val repeat = cmd.optInt("repeat")
                Runnable { key(action, code, meta, repeat) }
            }

            // Phone protocol, translated (desktop client, footer buttons)
            "tap" -> {
                val x = cmd.getDouble("x").toFloat()
                val y = cmd.getDouble("y").toFloat()
                Runnable { click(x, y) }
            }
            "long_press" -> {
                val x = cmd.getDouble("x").toFloat()
                val y = cmd.getDouble("y").toFloat()
                val duration = cmd.optLong("duration", 600)
                Runnable { mouse(DesktopProtocol.MOUSE_DOWN, x, y, BUTTON_PRIMARY, BUTTON_PRIMARY); Thread.sleep(duration); mouse(DesktopProtocol.MOUSE_UP, x, y, 0, BUTTON_PRIMARY) }
            }
            "swipe" -> {
                val x1 = cmd.getDouble("x1").toFloat()
                val y1 = cmd.getDouble("y1").toFloat()
                val x2 = cmd.getDouble("x2").toFloat()
                val y2 = cmd.getDouble("y2").toFloat()
                val duration = cmd.optLong("duration", 300)
                Runnable { drag(x1, y1, x2, y2, duration) }
            }
            "scroll" -> {
                val x = cmd.getDouble("x").toFloat()
                val y = cmd.getDouble("y").toFloat()
                // Phone protocol: dy < 0 scrolls up, one wheel notch is about 300
                val v = (-cmd.optDouble("dy", 0.0) / 300.0).toFloat().coerceIn(-10f, 10f)
                Runnable { scroll(x, y, 0f, v) }
            }
            "key" -> {
                val code = when (cmd.optString("action")) {
                    "back" -> KeyEvent.KEYCODE_BACK
                    "home" -> KeyEvent.KEYCODE_HOME
                    "recents" -> KeyEvent.KEYCODE_APP_SWITCH
                    "notifications" -> KeyEvent.KEYCODE_NOTIFICATION
                    else -> return false  // quick settings, power: global actions of the accessibility service
                }
                Runnable { press(code) }
            }
            "text" -> {
                val content = cmd.optString("content")
                Runnable {
                    // The virtual keyboard map has no key for characters like "ñ" or emoji;
                    // those go through the accessibility service's input connection.
                    if (!text(content)) RemoteAccessibilityService.handleCommand(json)
                }
            }
            "backspace" -> Runnable { press(KeyEvent.KEYCODE_DEL) }
            "delete" -> Runnable { press(KeyEvent.KEYCODE_FORWARD_DEL) }
            "select_all" -> Runnable { press(KeyEvent.KEYCODE_A, META_CTRL) }
            "copy" -> Runnable { press(KeyEvent.KEYCODE_C, META_CTRL) }
            "cut" -> Runnable { press(KeyEvent.KEYCODE_X, META_CTRL) }
            "paste" -> Runnable { press(KeyEvent.KEYCODE_V, META_CTRL) }
            else -> return false
        }
        try {
            worker.execute {
                try { run.run() } catch (_: InterruptedException) {} catch (t: Throwable) { Log.w(TAG, "command failed: $json", t) }
            }
        } catch (_: Exception) {
            return false  // released
        }
        return true
    }

    private fun click(x: Float, y: Float) {
        mouse(DesktopProtocol.MOUSE_MOVE, x, y, 0, 0)
        mouse(DesktopProtocol.MOUSE_DOWN, x, y, BUTTON_PRIMARY, BUTTON_PRIMARY)
        mouse(DesktopProtocol.MOUSE_UP, x, y, 0, BUTTON_PRIMARY)
    }

    private fun drag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val steps = max(2, (durationMs / 16).toInt()).coerceAtMost(60)
        val pause = (durationMs.toDouble() / steps).roundToLong()
        mouse(DesktopProtocol.MOUSE_DOWN, x1, y1, BUTTON_PRIMARY, BUTTON_PRIMARY)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            mouse(DesktopProtocol.MOUSE_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, BUTTON_PRIMARY, 0)
            Thread.sleep(pause)
        }
        mouse(DesktopProtocol.MOUSE_UP, x2, y2, 0, BUTTON_PRIMARY)
    }

    private fun press(code: Int, meta: Int = 0) {
        if (meta and META_CTRL != 0) key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, meta, 0)
        key(KeyEvent.ACTION_DOWN, code, meta, 0)
        key(KeyEvent.ACTION_UP, code, meta, 0)
        if (meta and META_CTRL != 0) key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
    }

    // ------------------------------------------------------------------------------------------
    // Transactions
    // ------------------------------------------------------------------------------------------

    private fun mouse(action: Int, x: Float, y: Float, buttons: Int, actionButton: Int) = oneway(DesktopProtocol.MOUSE) {
        it.writeInt(action); it.writeFloat(x); it.writeFloat(y); it.writeInt(buttons); it.writeInt(actionButton)
    }

    private fun scroll(x: Float, y: Float, h: Float, v: Float) = oneway(DesktopProtocol.SCROLL) {
        it.writeFloat(x); it.writeFloat(y); it.writeFloat(h); it.writeFloat(v)
    }

    private fun key(action: Int, code: Int, meta: Int, repeat: Int) = oneway(DesktopProtocol.KEY) {
        it.writeInt(action); it.writeInt(code); it.writeInt(meta); it.writeInt(repeat)
    }

    private fun text(content: String): Boolean {
        val b = binder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DesktopProtocol.DESCRIPTOR)
            data.writeString(content)
            b.transact(DesktopProtocol.TEXT, data, reply, 0)
            reply.readException()
            reply.readInt() == 1
        } catch (t: Throwable) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private inline fun oneway(code: Int, write: (Parcel) -> Unit) {
        val b = binder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DesktopProtocol.DESCRIPTOR)
            write(data)
            b.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            Log.w(TAG, "transaction $code failed", t)
        } finally {
            data.recycle()
        }
    }
}
