package org.projectdrive.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.ui.DriveContent
import org.projectdrive.app.ui.DriveTheme
import java.util.concurrent.atomic.AtomicInteger

/** Controlled Compose content in the existing test host. No app models, GPS,
 * network, Room or owner recordings are involved in these presentation checks. */
@RunWith(AndroidJUnit4::class)
class DriveContentTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun nodes():List<AccessibilityNodeInfo> {
        fun children(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = buildList {
            add(node)
            for(i in 0 until node.childCount)node.getChild(i)?.let{addAll(children(it))}
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::children).orEmpty()
    }
    private fun matching(label:String)=nodes().filter{it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)}
    private fun await(label:String) {
        val end=SystemClock.elapsedRealtime()+10000
        while(matching(label).isEmpty()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50)
        assertTrue("Missing accessible control/content: $label",matching(label).isNotEmpty())
    }
    private fun tap(label:String) {
        await(label)
        for(node in matching(label)) {
            var current:AccessibilityNodeInfo?=node
            repeat(5) {
                if(current?.isClickable==true && current?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)return
                current=current?.parent
            }
        }
        fail("Accessible control did not accept click: $label")
    }
    private fun show(
        scenario:ActivityScenario<ComponentActivity>,state:NavigationState=NavigationState(),
        recording:Boolean=false,sharing:Boolean=false,
        recenter:()->Unit={},finish:()->Unit={},stop:()->Unit={},
    ) {
        scenario.onActivity{activity->
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContent{DriveTheme{
                DriveContent(state,false,recording,sharing,recenter,{},finish,stop,
                    mapContent={Box(it.semantics{contentDescription="Map canvas"})},
                    navigationContent={back->Text("Route planner fixture");Button(onClick=back){Text("Close planner")}},
                    convoyContent={Text("Convoy fixture")},telemetryContent={Text("Journal fixture")})
            }}
        }
    }
    @Test fun shortcutsOpenTheCorrectToolAndReturnToTheMap() {
        ActivityScenario.launch(ComponentActivity::class.java).use{scenario->
            val recentered=AtomicInteger();show(scenario,recenter={recentered.incrementAndGet()})
            await("Map canvas");tap("Recenter");assertEquals(1,recentered.get())
            tap("Journal");await("Journal fixture");tap("Back to map");await("Map canvas")
            tap("Navigation");await("Route planner fixture");tap("Close planner");await("Map canvas")
            tap("Convoy");await("Convoy fixture");tap("Back to map");await("Map canvas")
        }
    }
    @Test fun activeRecordingAndSharingHaveDirectStopActionsOnTheMap() {
        ActivityScenario.launch(ComponentActivity::class.java).use{scenario->
            val finished=AtomicInteger();val stopped=AtomicInteger()
            show(scenario,recording=true,sharing=true,finish={finished.incrementAndGet()},stop={stopped.incrementAndGet()})
            await("Map canvas");tap("Finish recording");tap("Stop sharing now")
            assertEquals(1,finished.get());assertEquals(1,stopped.get())
        }
    }
    @Test fun incomingPlannerRequestOpensNavigationWithoutLocationOrRecording() {
        ActivityScenario.launch(ComponentActivity::class.java).use{scenario->
            show(scenario,state=NavigationState(plannerRequest=2))
            await("Route planner fixture");tap("Close planner");await("Map canvas")
            assertTrue(matching("Finish recording").isEmpty())
            assertTrue(matching("Stop sharing now").isEmpty())
        }
    }
}
