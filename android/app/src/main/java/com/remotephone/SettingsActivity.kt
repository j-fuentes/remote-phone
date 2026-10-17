package com.remotephone

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.remotephone.desktop.DesktopSession
import rikka.shizuku.Shizuku

class SettingsActivity : ComponentActivity() {

    private val refreshDesktop = Runnable { showDesktop() }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread(refreshDesktop) }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread(refreshDesktop) }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread(refreshDesktop) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bind(R.id.askToggle, Prefs.ASK, Prefs.ask(this))
        bind(R.id.rememberToggle, Prefs.REMEMBER, Prefs.remember(this))
        bind(R.id.multipleToggle, Prefs.MULTIPLE, Prefs.multiple(this))

        findViewById<Button>(R.id.forgetButton).setOnClickListener {
            Prefs.forgetRemembered(this)
            showRemembered()
        }

        findViewById<Switch>(R.id.desktopToggle).apply {
            isChecked = Prefs.desktop(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.set(this@SettingsActivity, Prefs.DESKTOP, checked)
                if (checked && DesktopSession.state() == DesktopSession.State.NO_PERMISSION) requestShizuku()
                showDesktop()
            }
        }
        findViewById<Button>(R.id.shizukuButton).setOnClickListener {
            when (DesktopSession.state()) {
                DesktopSession.State.NO_PERMISSION -> requestShizuku()
                else -> openShizuku()
            }
        }
        findViewById<Button>(R.id.desktopSizeButton).setOnClickListener {
            Prefs.nextDesktopSize(this)
            showDesktop()
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        showRemembered()  // an Allow may have happened while this screen was in the background
        showDesktop()     // Shizuku may have been started meanwhile
    }

    private fun bind(id: Int, key: String, value: Boolean) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> Prefs.set(this@SettingsActivity, key, checked) }
        }
    }

    private fun showRemembered() {
        val names = Prefs.remembered(this).values.map { it.name }.sorted()
        findViewById<TextView>(R.id.rememberedText).text =
            if (names.isEmpty()) "No remembered computers" else "Remembered: ${names.joinToString(", ")}"
        findViewById<Button>(R.id.forgetButton).isEnabled = names.isNotEmpty()
    }

    private fun shizukuInstalled(): Boolean = try {
        packageManager.getPackageInfo(DesktopSession.SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun showDesktop() {
        val enabled = Prefs.desktop(this)
        val state = DesktopSession.state()
        val status = findViewById<TextView>(R.id.desktopStatus)
        val button = findViewById<Button>(R.id.shizukuButton)
        status.text = when (state) {
            DesktopSession.State.UNSUPPORTED -> "Not available: needs Android 10 or later and Shizuku 11 or later."
            DesktopSession.State.NOT_RUNNING ->
                if (shizukuInstalled()) "Shizuku is installed but not running. Start it in the Shizuku app (wireless debugging, no computer needed)."
                else "Shizuku is not installed. Install it, start it with wireless debugging, then come back."
            DesktopSession.State.NO_PERMISSION -> "Shizuku is running. Allow RemotePhone to use it."
            DesktopSession.State.READY -> if (enabled) "Ready. The next Start Mirroring opens the desktop." else "Shizuku is ready."
        }
        button.visibility = if (state == DesktopSession.State.READY || state == DesktopSession.State.UNSUPPORTED) View.GONE else View.VISIBLE
        button.text = when {
            state == DesktopSession.State.NO_PERMISSION -> "Grant Shizuku access"
            shizukuInstalled() -> "Open Shizuku"
            else -> "Get Shizuku"
        }
        val (w, h, dpi) = Prefs.desktopSize(this)
        findViewById<Button>(R.id.desktopSizeButton).text = "Desktop size: $w × $h ($dpi dpi)"
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(DesktopSession.PERMISSION_REQUEST)
            else openShizuku()  // denied with "don't ask again": only Shizuku's app can change it
        } catch (_: Throwable) {
            showDesktop()
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(DesktopSession.SHIZUKU_PACKAGE)
        startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    }
}
