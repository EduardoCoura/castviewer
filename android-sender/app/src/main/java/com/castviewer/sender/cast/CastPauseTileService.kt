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
 * Android Quick Settings tile ("Pausar Cast") to freeze/resume video & audio
 * for the TV from anywhere (e.g. typing a password or fullscreen game).
 */
class CastPauseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val session = CastStateStore.session.value
        when (session.connectionState) {
            CastConnectionState.STREAMING,
            CastConnectionState.PAUSED -> {
                CastProjectionService.togglePause(this)
                val willPause = session.connectionState == CastConnectionState.STREAMING
                Toast.makeText(
                    this,
                    if (willPause) "Espelhamento pausado" else "Espelhamento retomado",
                    Toast.LENGTH_SHORT
                ).show()
                syncTile()
            }
            else -> openAppFromTile()
        }
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val session = CastStateStore.session.value
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_pause)

        when (session.connectionState) {
            CastConnectionState.STREAMING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Pausar Cast"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = session.activeDevice?.name ?: "Ao vivo"
                }
            }
            CastConnectionState.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Retomar Cast"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Pausado"
                }
            }
            else -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "Pausar Cast"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Desconectado"
                }
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
                402,
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
                    ComponentName(context.applicationContext, CastPauseTileService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
