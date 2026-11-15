package org.projectdrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.projectdrive.app.core.location.MapLocationPreference
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Opt-in foreground checks; never print/capture coordinates or alter owner records. */
@RunWith(AndroidJUnit4::class)
class CurrentLocationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun permitted()=listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED }
    private fun optIn()=assumeTrue(InstrumentationRegistry.getArguments().getString("currentLocationChecks")=="true")
    private fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<deadline) { if(condition())return;SystemClock.sleep(100) }
        fail(message)
    }
    private fun descendants(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = buildList {
        add(node);for(i in 0 until node.childCount)node.getChild(i)?.let { addAll(descendants(it)) }
    }
    private fun tap(label:String) {
        await("Accessible control unavailable: $label") {
            val node=instrumentation.uiAutomation.rootInActiveWindow?.let(::descendants)
                ?.firstOrNull { it.text?.toString()==label || it.contentDescription?.toString()==label }
            var candidate=node
            var clicked=false
            repeat(5) {
                candidate?.let { if(it.isClickable && !clicked)clicked=it.performAction(AccessibilityNodeInfo.ACTION_CLICK);candidate=it.parent }
            }
            clicked
        }
    }
    private fun findMap(view:View):MapView? {
        if(view is MapView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findMap(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun noPermissionOffersLocationWithoutRequestingItAutomatically() {
        optIn();assumeTrue(!permitted())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val state=ViewModelProvider(it)[NavigationViewModel::class.java].state.value
                assertFalse(state.gpsWanted);assertNull(state.fix)
                assertTrue(state.originText.isBlank());assertNull(state.destination)
            }
            tap("Use my location");tap("Keep location off")
            assertFalse("A dismissed disclosure changed native permission",permitted())
            scenario.onActivity { assertFalse(ViewModelProvider(it)[NavigationViewModel::class.java].state.value.gpsWanted) }
        }
    }
    @Test fun foregroundFixCentersMapAndRecenterResumesAfterExploration() {
        optIn();assumeTrue(permitted() && MapLocationPreference(context).enabled)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val map=AtomicReference<MapLibreMap?>()
            lateinit var model:NavigationViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model=ViewModelProvider(it)[NavigationViewModel::class.java]
                checkNotNull(findMap(it.window.decorView)).getMapAsync(map::set)
            }
            fun centered():Boolean {
                var result=false
                scenario.onActivity {
                    val fix=model.state.value.fix
                    val ready=map.get();val target=ready?.cameraPosition?.target
                    result=fix!=null && ready?.style?.isFullyLoaded==true && target!=null &&
                        abs(target.latitude-fix.coordinate.latitude)+abs(target.longitude-fix.coordinate.longitude)<.00001
                }
                return result
            }
            await("Fresh foreground location did not center the map",::centered)
            scenario.onActivity {
                val state=model.state.value
                assertTrue(state.gpsWanted);assertTrue(state.originText.isBlank());assertNull(state.destination)
                assertFalse(state.guiding);assertFalse(state.playback)
                assertFalse(ViewModelProvider(it)[org.projectdrive.app.feature.telemetry.TelemetryViewModel::class.java].state.value.recording)
                assertFalse(ViewModelProvider(it)[org.projectdrive.app.feature.convoy.ConvoyViewModel::class.java].state.value.sharing)
            }
            val bounds=Rect()
            scenario.onActivity { check(checkNotNull(findMap(it.window.decorView)).getGlobalVisibleRect(bounds)) }
            val down=SystemClock.uptimeMillis()
            for(i in 0..25) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),when(i){0->MotionEvent.ACTION_DOWN;25->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},
                    bounds.left+bounds.width()*(.75f-.5f*i/25),bounds.top+bounds.height()*.45f,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                try { check(instrumentation.uiAutomation.injectInputEvent(event,true)) } finally {event.recycle()}
                SystemClock.sleep(25)
            }
            SystemClock.sleep(1500)
            assertFalse("Native pan did not leave current-location follow",centered())
            // Only compare private viewport values in memory; failure messages contain no coordinates.
            var before:org.maplibre.android.camera.CameraPosition?=null
            scenario.onActivity {before=map.get()?.cameraPosition}
            SystemClock.sleep(2200)
            scenario.onActivity {
                val old=checkNotNull(before);val current=checkNotNull(map.get()).cameraPosition
                assertTrue("Location updates stole the explored camera",abs(checkNotNull(old.target).latitude-checkNotNull(current.target).latitude)+abs(checkNotNull(old.target).longitude-checkNotNull(current.target).longitude)<.00001)
            }
            tap("Recenter");await("Recenter did not return to current location",::centered)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {assertNull("Backgrounding retained the active location fix",model.state.value.fix)}
            scenario.moveToState(Lifecycle.State.RESUMED)
            await("Returning to the foreground did not reacquire location",::centered)
        }
    }
    @Test fun explicitOffSurvivesNewActivityAndRecenterCanEnableItAgain() {
        optIn();assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));assumeTrue(permitted())
        val preference=MapLocationPreference(context);val original=preference.enabled
        try {
            preference.enabled=true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                tap("Navigation");tap("Turn location off")
                scenario.onActivity {assertFalse(ViewModelProvider(it)[NavigationViewModel::class.java].state.value.gpsWanted)}
                assertFalse(preference.enabled)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {assertFalse(ViewModelProvider(it)[NavigationViewModel::class.java].state.value.gpsWanted)}
                tap("Recenter");tap("Continue")
                scenario.onActivity {
                    val state=ViewModelProvider(it)[NavigationViewModel::class.java].state.value
                    assertTrue(state.gpsWanted);assertTrue(state.originText.isBlank());assertNull(state.destination)
                }
                assertTrue(preference.enabled)
            }
        } finally { preference.enabled=original }
    }
}
