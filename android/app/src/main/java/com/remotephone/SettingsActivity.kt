package com.remotephone

import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private val ui = Handler(Looper.getMainLooper())
    private val announceName = Runnable { ScreenCaptureService.refreshInfo() }
    private val refreshInterfaces = Runnable { showInterfaces() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { queueInterfaces() }
        override fun onLost(network: Network) { queueInterfaces() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { queueInterfaces() }
    }
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
        // Desktop size: drag between 960×540 and 3840×2160 in 160 px steps
        findViewById<SeekBar>(R.id.desktopSizeBar).apply {
            max = (Prefs.DESKTOP_MAX_WIDTH - Prefs.DESKTOP_MIN_WIDTH) / Prefs.DESKTOP_STEP
            progress = (Prefs.desktopSize(this@SettingsActivity).first - Prefs.DESKTOP_MIN_WIDTH) / Prefs.DESKTOP_STEP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    val width = Prefs.DESKTOP_MIN_WIDTH + value * Prefs.DESKTOP_STEP
                    if (fromUser) Prefs.setDesktopWidth(this@SettingsActivity, width)
                    showDesktopSize(width)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        showDesktopSize(Prefs.desktopSize(this).first)

        // Phone name: applies to new connections at once and is re-sent to connected clients
        findViewById<EditText>(R.id.phoneNameInput).apply {
            setText(Prefs.customPhoneName(this@SettingsActivity))
            hint = Prefs.defaultPhoneName()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    Prefs.setPhoneName(this@SettingsActivity, s?.toString() ?: "")
                    ui.removeCallbacks(announceName)
                    ui.postDelayed(announceName, 600)
                }
            })
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
        showInterfaces()
        try {
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        ui.removeCallbacks(refreshInterfaces)
        super.onPause()
    }

    private fun queueInterfaces() {
        ui.removeCallbacks(refreshInterfaces)
        ui.postDelayed(refreshInterfaces, 400)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** One switch per network kind that is connected now, plus switched-off kinds that are not. */
    private fun showInterfaces() {
        val container = findViewById<LinearLayout>(R.id.interfacesContainer)
        container.removeAllViews()
        val present = NetworkAddresses.list(this).groupBy { it.kind }
        val disabled = Prefs.disabledInterfaces(this)
        val kinds = (present.keys + disabled.mapNotNull { name -> NetworkAddresses.Kind.values().firstOrNull { it.name == name } })
            .distinct().sortedBy { it.ordinal }
        if (kinds.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No network connected"
                textSize = 13f
                setTextColor(getColor(R.color.text_hint))
            })
        }
        for (kind in kinds) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = kind.label
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
            })
            texts.addView(TextView(this).apply {
                text = present[kind]?.joinToString(", ") { it.ip } ?: "Not connected"
                textSize = 11f
                setTextColor(getColor(R.color.text_hint))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Switch(this).apply {
                isChecked = kind.name !in disabled
                setOnCheckedChangeListener { _, on ->
                    Prefs.setInterfaceAllowed(this@SettingsActivity, kind.name, on)
                    ScreenCaptureService.rebind()
                    showInterfacesHint()
                }
            })
            container.addView(row)
        }
        showInterfacesHint()
    }

    private fun showInterfacesHint() {
        val present = NetworkAddresses.list(this)
        val anyOpen = present.any { Prefs.interfaceAllowed(this, it.kind.name) }
        findViewById<TextView>(R.id.interfacesHint).text =
            if (present.isNotEmpty() && !anyOpen) "⚠ Every connected network is switched off: no computer can connect."
            else "While mirroring, the phone only listens on the networks switched on here (mirroring and web client)."
    }

    private fun showDesktopSize(width: Int) {
        val (w, h, dpi) = Prefs.desktopSizeFor(width)
        findViewById<TextView>(R.id.desktopSizeLabel).text = "Desktop size: $w × $h ($dpi dpi)"
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
