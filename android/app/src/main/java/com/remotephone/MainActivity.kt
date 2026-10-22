package com.remotephone

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var ipText: TextView
    private lateinit var portText: TextView
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var audioToggle: Switch
    private lateinit var audioSubtext: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var accessibilityButton: Button
    private lateinit var keyboardStatus: TextView
    private lateinit var keyboardButton: Button
    private lateinit var requestsCard: View
    private lateinit var requestsContainer: LinearLayout

    private lateinit var webToggle: Switch
    private lateinit var webSubtext: TextView
    private lateinit var webUrlRow: LinearLayout

    // Refresh the addresses when WiFi or a VPN comes or goes, debounced
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshAddresses = Runnable { ipText.text = addressesText(); showWebClient() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { queueAddressRefresh() }
        override fun onLost(network: Network) { queueAddressRefresh() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { queueAddressRefresh() }
    }

    private var isStreaming = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startScreenCapture(result.resultCode, result.data!!)
        } else {
            statusText.text = "Permission denied"
            statusDot.setTextColor(getColor(R.color.status_red))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        ipText = findViewById(R.id.ipText)
        portText = findViewById(R.id.portText)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        audioToggle = findViewById(R.id.audioToggle)
        audioSubtext = findViewById(R.id.audioSubtext)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        requestsCard = findViewById(R.id.requestsCard)
        requestsContainer = findViewById(R.id.requestsContainer)

        // Show device IP
        ipText.text = addressesText()
        portText.text = "${ScreenCaptureService.WS_PORT}"

        // Start/Stop button
        startButton.setOnClickListener {
            if (!isStreaming) {
                requestScreenCapture()
            } else {
                stopScreenCapture()
            }
        }

        // Audio toggle
        audioToggle.setOnCheckedChangeListener { _, isChecked ->
            ScreenCaptureService.toggleAudio(isChecked)
        }

        // Audio only available on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioToggle.isEnabled = true
            audioSubtext.text = "Streams phone audio to PC"
        } else {
            audioToggle.isEnabled = false
            audioSubtext.text = "Requires Android 10+"
        }

        // Web client served from the phone: the URL opens a browser client pointed at this phone
        webToggle = findViewById(R.id.webToggle)
        webSubtext = findViewById(R.id.webSubtext)
        webUrlRow = findViewById(R.id.webUrlRow)
        webToggle.isChecked = Prefs.webClient(this)
        webToggle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.set(this, Prefs.WEB_CLIENT, isChecked)
            ScreenCaptureService.setWebClient(isChecked)
            showWebClient()
        }

        // Phone screen while desktop mode streams
        findViewById<View>(R.id.screenOffButton).setOnClickListener { ScreenCaptureService.setPhoneScreenOff(true) }
        findViewById<View>(R.id.blackoutButton).setOnClickListener {
            ScreenCaptureService.setPhoneBlackout(!ScreenCaptureService.phoneBlackout())
        }

        // Accessibility settings button
        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Android 13+ types through the accessibility service's own input
        // connection, so the extra keyboard (and its card) is only wired on older versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findViewById<android.view.View>(R.id.keyboardCard).visibility = android.view.View.GONE
        } else {
            keyboardStatus = findViewById(R.id.keyboardStatus)
            keyboardButton = findViewById(R.id.keyboardButton)
            keyboardButton.setOnClickListener {
                if (isRemoteKeyboardEnabled()) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                } else {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // Without NOT_VPN removed, the request would skip VPN networks
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}
        updateAccessibilityStatus()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) updateKeyboardStatus()
        ipText.text = addressesText()
        showWebClient()
        ScreenCaptureService.onClientsChanged = ::showStreaming
        ScreenCaptureService.onRunningChanged = ::syncStreaming
        syncStreaming()
    }

    /** Started or stopped elsewhere (Quick Settings tile, notification, system): follow it. */
    private fun syncStreaming() {
        val clients = ScreenCaptureService.clients()
        if (clients != null) showStreaming(clients)
        else if (isStreaming && !ScreenCaptureService.isActive()) showStopped()
        showPhoneScreenCard()
    }

    /** Desktop mode streaming: the phone's own screen can go dark while the desktop keeps running. */
    private fun showPhoneScreenCard() {
        val card = findViewById<View>(R.id.phoneScreenCard)
        card.visibility = if (ScreenCaptureService.phoneScreenControls()) View.VISIBLE else View.GONE
        findViewById<android.widget.Button>(R.id.blackoutButton).text =
            if (ScreenCaptureService.phoneBlackout()) "Remove black screen" else "Black screen"
    }

    override fun onPause() {
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        uiHandler.removeCallbacks(refreshAddresses)
        ScreenCaptureService.onClientsChanged = null
        ScreenCaptureService.onRunningChanged = null
        super.onPause()
    }

    /** Streaming state with who is watching, so a silent viewer on the network is never invisible. */
    private fun showStreaming(clients: List<String>) {
        isStreaming = true
        startButton.text = "Stop Mirroring"
        statusDot.text = "\u25CF"
        if (clients.isEmpty()) {
            statusText.text = "Streaming, waiting for a client"
            statusDot.setTextColor(getColor(R.color.status_amber))
        } else {
            statusText.text = "Streaming to ${clients.joinToString(", ")}"
            statusDot.setTextColor(getColor(R.color.status_green))
        }
        showRequests(ScreenCaptureService.pendingRequests())
    }

    /** Computers waiting for an answer, with Allow and Deny, so a request never depends on the notification. */
    private fun showRequests(requests: List<MirrorWebSocketServer.PendingRequest>) {
        requestsCard.visibility = if (requests.isEmpty()) View.GONE else View.VISIBLE
        requestsContainer.removeAllViews()
        for ((id, name, address) in requests) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = if (name.isBlank()) address else "$name\n$address"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(answerButton("Allow", R.color.status_green) { ScreenCaptureService.answer(id, true) })
            row.addView(answerButton("Deny", R.color.status_red) { ScreenCaptureService.answer(id, false) })
            requestsContainer.addView(row)
        }
    }

    private fun answerButton(label: String, color: Int, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(getColor(color))
            setOnClickListener { onClick() }
        }

    private fun queueAddressRefresh() {
        uiHandler.removeCallbacks(refreshAddresses)
        uiHandler.postDelayed(refreshAddresses, 400)  // onLost fires before the network leaves the list
    }

    /** Every reachable address, local network first, labelled when there is more than one. */
    private fun addressesText(): String {
        val list = NetworkAddresses.allowed(this)
        return when {
            list.isEmpty() && NetworkAddresses.list(this).isNotEmpty() -> "All networks switched off in Settings"
            list.isEmpty() -> "Not connected to WiFi"
            list.size == 1 && list[0].kind == NetworkAddresses.Kind.WIFI -> list[0].ip
            else -> list.joinToString("\n") { "${it.ip}  · ${it.kind.label}" }
        }
    }

    /** One URL per address, each with its own Share button; the shared link carries host, port and fingerprint. */
    private fun showWebClient() {
        val on = webToggle.isChecked
        val addresses = NetworkAddresses.allowed(this)
        webUrlRow.removeAllViews()
        webUrlRow.visibility = if (on && addresses.isNotEmpty()) View.VISIBLE else View.GONE
        if (on) for (address in addresses) webUrlRow.addView(urlRow(address, addresses.size > 1))
        webSubtext.text = when {
            !on -> "Open this phone from any browser, nothing to install"
            addresses.isEmpty() -> "Connect to WiFi to get an address"
            !isStreaming -> "Available while mirroring. Accept the certificate warning once in the browser."
            else -> "Open in a browser on the same network. Accept the certificate warning once."
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun urlRow(address: NetworkAddresses.Address, labelled: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = "https://${address.ip}:${WebClientServer.PORT}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
            setTextIsSelectable(true)
        })
        if (labelled) texts.addView(TextView(this).apply {
            text = when (address.kind) {
                NetworkAddresses.Kind.VPN -> "VPN · for devices on the same VPN"
                NetworkAddresses.Kind.MOBILE -> "Mobile data · usually not reachable"
                else -> address.kind.label
            }
            textSize = 11f
            setTextColor(getColor(R.color.text_hint))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Share"
            setTextColor(getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.button_primary)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { shareWebClient(address.ip) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) })
        return row
    }

    private fun shareWebClient(ip: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "RemotePhone")
            putExtra(Intent.EXTRA_TEXT, WebClientServer.url(ip))
        }
        startActivity(Intent.createChooser(send, "Share web client link"))
    }

    private fun requestScreenCapture() {
        // Desktop mode through Shizuku: no "share a single app or the entire screen" dialog
        if (ScreenCaptureService.canStartWithoutConsent(this)) {
            ScreenCaptureService.startWithoutConsent(this)
            showStreaming(emptyList())
            showWebClient()
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        showStreaming(emptyList())
        showWebClient()
    }

    private fun stopScreenCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        showStopped()
    }

    private fun showStopped() {
        isStreaming = false
        showRequests(emptyList())
        startButton.text = "Start Mirroring"
        statusText.text = "Ready to stream"
        statusDot.text = "○"
        statusDot.setTextColor(getColor(R.color.text_muted))
        showWebClient()
        findViewById<View>(R.id.phoneScreenCard).visibility = View.GONE
    }

    private fun updateAccessibilityStatus() {
        if (RemoteAccessibilityService.isRunning()) {
            accessibilityStatus.text = "Accessibility Service: ✓ Enabled"
            accessibilityStatus.setTextColor(getColor(R.color.status_green))
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                accessibilityStatus.text = "Accessibility Service: Not enabled\n⚠ On Android 13+: go to Settings → Apps → RemotePhone → ⋮ menu → \"Allow restricted settings\" first"
            } else {
                accessibilityStatus.text = "Accessibility Service: Not enabled"
            }
            accessibilityStatus.setTextColor(getColor(R.color.status_amber))
        }
    }

    private fun isRemoteKeyboardEnabled(): Boolean {
        // Settings.Secure.ENABLED_INPUT_METHODS is not readable from targetSdk 34;
        // InputMethodManager is the supported way to list enabled keyboards.
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun updateKeyboardStatus() {
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        when {
            selected -> {
                keyboardStatus.text = "RemotePhone Keyboard: \u2713 Active"
                keyboardStatus.setTextColor(getColor(R.color.status_green))
                keyboardButton.text = "Switch keyboard"
            }
            isRemoteKeyboardEnabled() -> {
                keyboardStatus.text = "RemotePhone Keyboard: enabled, not selected"
                keyboardStatus.setTextColor(getColor(R.color.status_amber))
                keyboardButton.text = "Switch keyboard"
            }
            else -> {
                keyboardStatus.text = "RemotePhone Keyboard: not enabled"
                keyboardStatus.setTextColor(getColor(R.color.status_amber))
                keyboardButton.text = "Enable RemotePhone Keyboard"
            }
        }
    }
}
