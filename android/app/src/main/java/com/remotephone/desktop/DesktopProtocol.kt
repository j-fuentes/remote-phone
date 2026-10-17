package com.remotephone.desktop

/**
 * Wire format between the app and [DesktopUserService], which runs in Shizuku's shell process.
 * A plain Binder with hand-written parcels: no AIDL, no hidden API stubs on the app side.
 */
object DesktopProtocol {
    const val DESCRIPTOR = "com.remotephone.desktop.IDesktopService"

    /** Bumped whenever the service code changes, so Shizuku restarts a stale service process. */
    const val VERSION = 1

    const val CREATE_DISPLAY = 1   // in: width, height, dpi, Surface   out: displayId (or -1), error
    const val RELEASE_DISPLAY = 2
    const val MOUSE = 3            // oneway in: action, x, y, buttons, actionButton
    const val SCROLL = 4           // oneway in: x, y, h, v
    const val KEY = 5              // oneway in: action, keycode, metaState, repeat
    const val TEXT = 6             // in: text   out: 1 when every character could be typed

    /** Shizuku's destroy() transaction (AIDL code 16777114 + FIRST_CALL_TRANSACTION). */
    const val DESTROY = 16777115

    const val MOUSE_DOWN = 0
    const val MOUSE_UP = 1
    const val MOUSE_MOVE = 2
}
