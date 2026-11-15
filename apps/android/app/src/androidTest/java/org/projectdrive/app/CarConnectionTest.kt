package org.projectdrive.app

import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.car.*

@RunWith(AndroidJUnit4::class)
class CarConnectionTest {
    @Test fun connectionChangesAreObservedOnlyWhileNeeded() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var activations=0;var removals=0
            val source=object:MutableLiveData<Int>() {
                override fun onActive(){super.onActive();activations++}
                override fun onInactive(){super.onInactive();removals++}
            }
            val monitor=CarConnectionMonitor{source}
            monitor.setMonitoring(true);monitor.setMonitoring(true)
            assertEquals(1,activations);assertEquals(CarConnectionState.Checking,monitor.state.value)
            source.value=2;assertEquals(CarConnectionState.AndroidAuto,monitor.state.value)
            source.value=0;assertEquals(CarConnectionState.NotDetected,monitor.state.value)
            source.value=1;assertEquals(CarConnectionState.Automotive,monitor.state.value)
            source.value=99;assertEquals(CarConnectionState.Unavailable,monitor.state.value)
            monitor.setMonitoring(false)
            assertEquals(1,removals);assertFalse(source.hasActiveObservers())
            source.value=2;assertEquals(CarConnectionState.Checking,monitor.state.value)
            monitor.setMonitoring(true);assertEquals(CarConnectionState.AndroidAuto,monitor.state.value)
            monitor.setMonitoring(false);assertFalse(source.hasObservers())
        }
    }
    @Test fun unavailableProviderDoesNotCrashOrStartAnything() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var attempts=0
            val monitor=CarConnectionMonitor{attempts++;throw SecurityException("Synthetic unavailable provider")}
            monitor.setMonitoring(true);assertEquals(CarConnectionState.Unavailable,monitor.state.value)
            monitor.setMonitoring(true);assertEquals(1,attempts)
            monitor.setMonitoring(false);assertEquals(CarConnectionState.Checking,monitor.state.value)
        }
    }
    @Test fun nativeConnectionApiCanBeQueriedAndStoppedWithoutVehicleOrLocationAccess() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var monitor:CarConnectionMonitor
        instrumentation.runOnMainSync {monitor=CarConnectionMonitor(instrumentation.targetContext);monitor.setMonitoring(true)}
        try {
            val deadline=SystemClock.elapsedRealtime()+10_000
            while(monitor.state.value==CarConnectionState.Checking&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50)
            assertNotEquals(CarConnectionState.Checking,monitor.state.value)
            // Native absence is a valid result; this does not simulate a real head unit.
            assertNotEquals(CarConnectionState.Unavailable,monitor.state.value)
        } finally {instrumentation.runOnMainSync{monitor.setMonitoring(false)}}
    }
}
