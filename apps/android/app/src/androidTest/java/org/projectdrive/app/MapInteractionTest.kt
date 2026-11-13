package org.projectdrive.app

import android.os.SystemClock
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Opt-in real native gestures on a fixed public route, with GPS off. */
@RunWith(AndroidJUnit4::class)
class MapInteractionTest {
    @Test fun panPinchRecenterAndRotationRespectExploration() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mapGestureChecks")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun waitFor(message:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+30000
            while(!condition() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
            assertTrue(message,condition())
        }
        fun tap(text:String) {
            var clicked=false
            waitFor("Accessible control missing: $text") {
                if(clicked) true else {
                    // Compose virtual nodes do not implement platform text-search queries.
                    fun descendants(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = buildList {
                        add(node)
                        for(index in 0 until node.childCount) node.getChild(index)?.let { addAll(descendants(it)) }
                    }
                    val matches=instrumentation.uiAutomation.rootInActiveWindow?.let(::descendants).orEmpty()
                    for(node in matches.filter{it.text?.toString()==text}) {
                        var candidate:AccessibilityNodeInfo?=node
                        repeat(4) {
                            val current=candidate
                            if(current!=null && current.isClickable) clicked=current.performAction(AccessibilityNodeInfo.ACTION_CLICK) || clicked
                            candidate=current?.parent
                        }
                        if(clicked) break
                    }
                    clicked
                }
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var model:NavigationViewModel?=null
            val map=AtomicReference<MapLibreMap?>()
            fun bindMap() {
                map.set(null)
                fun find(view:View):MapView? {
                    if(view is MapView)return view
                    if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                    return null
                }
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    model=ViewModelProvider(activity)[NavigationViewModel::class.java]
                    checkNotNull(find(activity.window.decorView)).getMapAsync { map.set(it) }
                }
                waitFor("Map style did not load") { var ready=false;scenario.onActivity{ready=map.get()?.style?.isFullyLoaded==true};ready }
            }
            data class Viewport(val latitude:Double,val longitude:Double,val zoom:Double)
            fun camera():Viewport {
                var value:Viewport?=null;scenario.onActivity{val c=checkNotNull(map.get()).cameraPosition;val t=checkNotNull(c.target);value=Viewport(t.latitude,t.longitude,c.zoom)};return checkNotNull(value)
            }
            fun delta(a:Viewport,b:Viewport)=abs(a.latitude-b.latitude)+abs(a.longitude-b.longitude)
            fun pan() {
                val bounds=Rect()
                scenario.onActivity { activity ->
                    fun find(view:View):MapView? {
                        if(view is MapView)return view
                        if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                        return null
                    }
                    check(checkNotNull(find(activity.window.decorView)).getGlobalVisibleRect(bounds))
                }
                val down=SystemClock.uptimeMillis()
                for(i in 0..25) {
                    val action=when(i){0->MotionEvent.ACTION_DOWN;25->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
                    val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.left+bounds.width()*(.72f-.44f*i/25),bounds.top+bounds.height()*.5f,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                    try { check(instrumentation.uiAutomation.injectInputEvent(motion,true)) } finally { motion.recycle() }
                    SystemClock.sleep(25)
                }
                SystemClock.sleep(1800)
            }
            fun pinch() {
                var bounds=Rect()
                scenario.onActivity { activity ->
                    fun find(view:View):MapView? {
                        if(view is MapView)return view
                        if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let{return it}
                        return null
                    }
                    check(checkNotNull(find(activity.window.decorView)).getGlobalVisibleRect(bounds))
                }
                val cx=bounds.exactCenterX();val cy=bounds.exactCenterY()
                // SDK rejects scale speeds below 0.6 dp/ms; use a normal brisk pinch.
                val start=bounds.width()*.06f;val end=bounds.width()*.34f
                val down=SystemClock.uptimeMillis()
                val properties=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                fun event(action:Int,count:Int,radius:Float) {
                    val coordinates=Array(count){i->MotionEvent.PointerCoords().apply{x=cx+if(i==0)-radius else radius;y=cy;pressure=1f;size=1f}}
                    val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,properties,coordinates,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                    try{check(instrumentation.uiAutomation.injectInputEvent(motion,true))}finally{motion.recycle()}
                    SystemClock.sleep(8)
                }
                event(MotionEvent.ACTION_DOWN,1,start)
                event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,start)
                for(i in 1..8)event(MotionEvent.ACTION_MOVE,2,start+(end-start)*i/8)
                event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,end)
                event(MotionEvent.ACTION_UP,1,end)
            }
            bindMap();assertFalse(checkNotNull(model).state.value.gpsWanted)
            val gestures=java.util.concurrent.atomic.AtomicInteger()
            scenario.onActivity { checkNotNull(map.get()).addOnCameraMoveStartedListener { reason -> if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)gestures.incrementAndGet() } }

            scenario.onActivity{checkNotNull(model).previewRoute()}
            waitFor("Public sample route unavailable"){checkNotNull(model).state.value.route!=null}
            Thread.sleep(1200)
            println("Stage: public preview ready");val overview=camera();pan();val explored=camera()
            println("Native gesture callbacks: ${gestures.get()}; camera changed: ${delta(overview,explored)>.0001}")
            assertTrue("Preview pan did not move native camera",delta(overview,explored)>.0001)
            Thread.sleep(2200);assertTrue("UI refresh stole manually panned camera",delta(explored,camera())<.00001)
            println("Stage: preview pan remains free");pinch()
            Thread.sleep(1000);val zoomed=camera();assertTrue("Native two-finger pinch did not zoom",zoomed.zoom>explored.zoom+.15)
            println("Stage: native pinch zoomed");scenario.recreate();bindMap();Thread.sleep(1200)
            assertTrue("Rotation reset manual viewport",delta(zoomed,camera())<.00001)
            assertTrue("Rotation reset manual zoom",abs(zoomed.zoom-camera().zoom)<.1)
            println("Stage: recreation preserved viewport");tap("Recenter");Thread.sleep(900)
            assertTrue("Recenter did not restore route overview",delta(overview,camera())<.0001)
            assertTrue("Recenter did not restore overview zoom",abs(overview.zoom-camera().zoom)<.15)
            println("Stage: recenter restored overview");scenario.onActivity{checkNotNull(model).playRoute()};Thread.sleep(1300)
            pan();val playbackPan=camera();Thread.sleep(2200)
            assertTrue("Playback updates stole manually panned camera",delta(playbackPan,camera())<.00001)
            println("Stage: playback pan remains free");tap("Recenter");Thread.sleep(1000)
            var centered=false
            scenario.onActivity {
                val p=checkNotNull(model).state.value.simulatedPosition
                val c=checkNotNull(map.get()).cameraPosition
                centered=p!=null && abs(checkNotNull(c.target).latitude-p.latitude)+abs(checkNotNull(c.target).longitude-p.longitude)<.00001 && abs(c.zoom-16)<.1
            }
            assertTrue("Recenter did not resume following playback position",centered)
            scenario.onActivity{checkNotNull(model).stopGuidance()}
        }
    }

}
