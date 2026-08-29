package com.kavach.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.kavach.app.KavachApplication
import com.kavach.app.ui.ShieldOverlayActivity

/**
 * The manual door.
 *
 * Automatic detection covers the case we designed for, but it depends on a
 * permission chain the user may not have finished and on an OEM honouring a
 * background activity launch. A Quick Settings tile depends on neither. It is
 * reachable in one swipe and one tap from anywhere on the phone — including over
 * the in-call screen and from the lock screen — without leaving the call.
 *
 * It also happens to be legally the cleanest way in: the tap launches an
 * activity, the activity is visible, and a visible app may start a microphone
 * foreground service with no exemption required at all.
 */
class KavachTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val monitoring =
            (application as? KavachApplication)
                ?.controller
                ?.state
                ?.value
                ?.monitoring == true
        qsTile?.apply {
            state = if (monitoring) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent =
            Intent(this, ShieldOverlayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
