package com.castviewer.sender.cast

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.castviewer.sender.MainActivity
import com.castviewer.sender.R
import com.castviewer.sender.model.CastConnectionState
import com.castviewer.sender.model.CastStateStore

/**
 * Android Quick Settings tile ("Clip 30s") to save the last 30 seconds
 * directly from the notification shade without leaving a fullscreen game.
 */
class CastQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val session = CastStateStore.session.value
        val isLive = session.connectionState == CastConnectionState.STREAMING ||
            session.connectionState == CastConnectionState.PAUSED

        if (isLive) {
            CastProjectionService.saveReplayClip(this)
            Toast.makeText(this, "Salvando Clip 30s em Movies/CastViewer...", Toast.LENGTH_SHORT).show()
            syncTile()
        } else {
            openAppFromTile()
        }
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val session = CastStateStore.session.value
        val isLive = session.connectionState == CastConnectionState.STREAMING ||
            session.connectionState == CastConnectionState.PAUSED

        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_clip)
        if (isLive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Clip 30s"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (session.isSavingClip) "Salvando..." else "Ao vivo • Toque"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "CastViewer"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Iniciar espelhamento"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppFromTile() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                401,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }

    companion object {
        fun requestSync(context: Context) {
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, CastQuickTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
