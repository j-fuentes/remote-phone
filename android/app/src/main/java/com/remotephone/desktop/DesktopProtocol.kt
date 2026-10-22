package com.remotephone.desktop

/**
 * Wire format between the app and [DesktopUserService], which runs in Shizuku's shell process.
 * A plain Binder with hand-written parcels: no AIDL, no hidden API stubs on the app side.
 */
object DesktopProtocol {
    const val DESCRIPTOR = "com.remotephone.desktop.IDesktopService"

    /** Bumped whenever the service code changes, so Shizuku restarts a stale service process. */
    const val VERSION = 2

    const val CREATE_DISPLAY = 1   // in: width, height, dpi, Surface   out: displayId (or -1), error
    const val RELEASE_DISPLAY = 2
    const val MOUSE = 3            // oneway in: action, x, y, buttons, actionButton
    const val SCROLL = 4           // oneway in: x, y, h, v
    const val KEY = 5              // oneway in: action, keycode, metaState, repeat
    const val TEXT = 6             // in: text   out: 1 when every character could be typed
    const val AUDIO_START = 7      // out: error (null when capturing)
    const val AUDIO_READ = 8       // in: max bytes   out: byte[] (empty when stopped)
    const val AUDIO_STOP = 9
    const val SCREEN_POWER = 10    // in: 1 on / 0 off   out: error (null when applied)

    /** Shizuku's destroy() transaction (AIDL code 16777114 + FIRST_CALL_TRANSACTION). */
    const val DESTROY = 16777115

    const val MOUSE_DOWN = 0
    const val MOUSE_UP = 1
    const val MOUSE_MOVE = 2
}
