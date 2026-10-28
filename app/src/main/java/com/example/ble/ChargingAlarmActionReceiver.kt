package com.example.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver triggered when the user taps "Stop Alarm" on the ongoing charging alert notification.
 */
class ChargingAlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == IqubeBleManager.ACTION_STOP_CHARGING_ALARM) {
            IqubeBleManager.getInstance(context).stopChargingAlarm()
        }
    }
}
