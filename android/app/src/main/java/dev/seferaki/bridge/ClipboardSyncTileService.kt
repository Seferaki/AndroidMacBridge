package dev.seferaki.bridge

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** One-shot action tile. It never reads clipboard from this service. */
class ClipboardSyncTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.label = getString(R.string.clipboard_tile_label)
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 30) {
                tile.contentDescription = getString(R.string.clipboard_tile_description)
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchSendPanel() }
        } else {
            launchSendPanel()
        }
    }

    private fun launchSendPanel() {
        val intent = Intent(this, ClipboardSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
