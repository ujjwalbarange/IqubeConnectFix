package com.example.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager

/**
 * Real Android Telephony BroadcastReceiver that monitors incoming calls, resolves contact names
 * from ContactsContract, and routes ringing and ended states directly to [IqubeBleManager]
 * for cluster caller ID display (`0x5B 0x43`, `0x5B 0x5D 0x43`, and `0x5B 0x4A` Byte 12).
 */
class PhoneCallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val bleManager = IqubeBleManager.getInstance(context)

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val callerName = resolveContactName(context, incomingNumber)
                bleManager.triggerIncomingCallAlert(callerName)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (bleManager.incomingCallState.value.isRinging) {
                    bleManager.endIncomingCallAlert()
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered: keep active until idle
            }
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "Caller"
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()?.ifBlank { phoneNumber } ?: phoneNumber
    }
}
