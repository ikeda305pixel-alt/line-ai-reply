package com.antigravity.lineaireply.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class PayPayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isServiceRunning = LineAccessibilityService.instance != null || isAccessibilityServiceEnabled()

        if (isServiceRunning) {
            // Disable service immediately and post 1-tap restore notification
            LineAccessibilityService.instance?.disableSelfWithRestoreNotification("PayPay") ?: run {
                LineAccessibilityService.showRestoreNotification(this, "PayPay")
            }

            // Launch PayPay directly if installed
            val payPayIntent = packageManager.getLaunchIntentForPackage("jp.ne.paypay.android.app")
            if (payPayIntent != null) {
                payPayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        2001,
                        payPayIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(payPayIntent)
                }
            } else {
                Toast.makeText(this, "💳 AI返信を一時停止しました（通知から1タップで再開可能）", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Already disabled: 1-tap jump to Accessibility settings
            val restoreIntent = LineAccessibilityService.getRestoreIntent(this)
            restoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    2002,
                    restoreIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(restoreIntent)
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isServiceRunning = LineAccessibilityService.instance != null || isAccessibilityServiceEnabled()

        if (isServiceRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "PayPay決済 (AI停止)"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "タップで停止＆PayPay起動"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "AI返信再開"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "タップで設定を開く"
            }
        }
        tile.updateTile()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val myService = ComponentName(this, LineAccessibilityService::class.java).flattenToString()
        return enabledServices.contains(myService, ignoreCase = true)
    }
}
