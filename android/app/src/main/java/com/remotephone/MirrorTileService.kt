package com.remotephone

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile: one tap starts or stops mirroring without opening the app.
 *
 * Stopping is direct. Starting needs the system's screen capture consent, which only an activity
 * can ask for, so the tile opens [MirrorStartActivity] (invisible) and collapses the shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
class MirrorTileService : TileService() {

    companion object {
        /** Set while the shade shows the tile, so a start/stop redraws it at once. */
        @Volatile var listening: (() -> Unit)? = null

        /** Ask the system to refresh the tile; safe to call from anywhere, any time. */
        fun refresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            try {
                requestListeningState(context, ComponentName(context, MirrorTileService::class.java))
            } catch (_: Exception) { /* tile not added, or the system refused: nothing to update */ }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = { update() }
        update()
    }

    override fun onStopListening() {
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenCaptureService.isActive()) {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            update(forceActive = false)
            return
        }
        // The consent dialog must not appear over the lock screen
        if (isLocked) unlockAndRun { openStarter() } else openStarter()
    }

    private fun openStarter() {
        val intent = Intent(this, MirrorStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // From Android 14 the Intent overload throws for apps targeting 34
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update(forceActive: Boolean? = null) {
        val tile = qsTile ?: return
        val active = forceActive ?: ScreenCaptureService.isActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mirror)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !active -> getString(R.string.tile_off)
                ScreenCaptureService.isStarting() -> getString(R.string.tile_starting)
                else -> ScreenCaptureService.clients()?.size?.takeIf { it > 0 }
                    ?.let { resources.getQuantityString(R.plurals.tile_watching, it, it) }
                    ?: getString(R.string.tile_on)
            }
        }
        tile.updateTile()
    }
}
