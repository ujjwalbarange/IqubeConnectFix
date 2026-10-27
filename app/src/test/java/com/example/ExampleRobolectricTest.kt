package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ble.IqubeBleManager
import com.example.model.BleConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context and verify initial disconnected BLE state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("iQube Cockpit", appName)

        val bleManager = IqubeBleManager(context)
        assertEquals(BleConnectionPhase.DISCONNECTED, bleManager.connectionPhase.value)
        assertNull(bleManager.packetParser.telemetryState.value.batteryPercent)
        assertNull(bleManager.packetParser.telemetryState.value.speedKmh)
    }
}
