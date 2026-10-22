package com.remotephone

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible activity behind the Quick Settings tile: shows the system screen capture consent,
 * starts mirroring if granted, and closes. The same steps as Start Mirroring in [MainActivity].
 */
class MirrorStartActivity : ComponentActivity() {

    private val forcePhone by lazy { intent.getBooleanExtra(ScreenCaptureService.EXTRA_FORCE_PHONE, false) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_FORCE_PHONE, forcePhone)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } else {
            Toast.makeText(this, R.string.tile_denied, Toast.LENGTH_SHORT).show()
        }
        MirrorTileService.refresh(this)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started twice (double tap on the tile) or already running: nothing to ask
        if (ScreenCaptureService.isActive()) { finish(); return }
        // Recreated after rotation: the consent dialog is already up
        if (savedInstanceState != null) return
        // Desktop mode through Shizuku needs no consent; desktop mode that failed asks for it
        if (!forcePhone && ScreenCaptureService.canStartWithoutConsent(this)) {
            ScreenCaptureService.startWithoutConsent(this)
            finish()
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
