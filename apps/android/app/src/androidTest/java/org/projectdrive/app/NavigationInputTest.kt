package org.projectdrive.app

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.projectdrive.app.core.navigation.*

@RunWith(AndroidJUnit4::class)
class NavigationInputTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+5000
        while(SystemClock.elapsedRealtime()<deadline){var ready=false;instrumentation.runOnMainSync{ready=condition()};if(ready)return;SystemClock.sleep(50)}
        fail(message)
    }
    @Test fun blankEndpointsLatestSearchAndExplicitSelection() {
        val pending=mutableMapOf<String,CompletableDeferred<List<Place>>>()
        val search=object:PlaceSearch { override suspend fun search(query:String):List<Place> {
            val result=CompletableDeferred<List<Place>>();pending[query]=result;return result.await()
        }}
        val store=ViewModelStore();lateinit var model:NavigationViewModel
        instrumentation.runOnMainSync {
            val owner=object:ViewModelStoreOwner {override val viewModelStore=store}
            model=ViewModelProvider(owner,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun<T:ViewModel> create(modelClass:Class<T>):T=NavigationViewModel(search) as T
            })[NavigationViewModel::class.java]
        }
        try {
            instrumentation.runOnMainSync {
                assertEquals("",model.state.value.originText);assertNull(model.state.value.destination)
                model.setSearchQuery("Nakuru");model.searchPlaces()
            }
            await("first search did not start"){pending.containsKey("Nakuru")}
            instrumentation.runOnMainSync {model.searchFrom(true);model.setSearchQuery("Nairobi");model.searchPlaces()}
            await("second search did not start"){pending.containsKey("Nairobi")}
            val nairobi=Place("node/1","Nairobi","city","Kenya",Coordinate(-1.28,36.82))
            val nakuru=Place("node/2","Nakuru","city","Kenya",Coordinate(-0.28,36.07))
            instrumentation.runOnMainSync {checkNotNull(pending["Nakuru"]).complete(listOf(nakuru));checkNotNull(pending["Nairobi"]).complete(listOf(nairobi))}
            await("latest result missing"){!model.state.value.searching&&model.state.value.places==listOf(nairobi)}
            instrumentation.runOnMainSync {
                model.selectPlace(nairobi);assertEquals("Nairobi",model.state.value.originLabel);assertNull(model.state.value.destination)
                model.searchFrom(false);model.selectPlace(nakuru)
                assertEquals("Nakuru",model.state.value.destinationLabel);assertEquals(nakuru.coordinate,model.state.value.destination)
                assertFalse(model.state.value.gpsWanted);assertNull(model.state.value.route)
                model.searchFrom(true);model.acceptSharedText("geo:-1.3,36.8?q=-1.3,36.8(Shared%20start)",true)
            }
            await("shared origin not selected"){model.state.value.originLabel=="Shared start"}
            instrumentation.runOnMainSync {assertEquals(nakuru.coordinate,model.state.value.destination);assertFalse(model.state.value.gpsWanted)}
        }finally{instrumentation.runOnMainSync{store.clear()}}
    }
    @Test fun explicitStartSurvivesALateFirstFixOnEmulator() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        val store=ViewModelStore();lateinit var model:NavigationViewModel
        instrumentation.runOnMainSync {
            val owner=object:ViewModelStoreOwner {override val viewModelStore=store}
            model=ViewModelProvider(owner,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun<T:ViewModel> create(modelClass:Class<T>):T=NavigationViewModel() as T
            })[NavigationViewModel::class.java]
            model.requestLocation()
            model.acceptSharedText("geo:-0.28,36.07?q=-0.28,36.07(Selected%20start)",true)
        }
        try {
            await("shared origin not selected"){model.state.value.originLabel=="Selected start"}
            instrumentation.runOnMainSync {
                // Isolated ViewModel fixture only. No Android location provider or sharing.
                model.onLocation(org.projectdrive.app.core.location.LocationFix(Coordinate(-1.28,36.82),5f,SystemClock.elapsedRealtime()))
                assertEquals("Selected start",model.state.value.originLabel)
                assertEquals(Coordinate(-0.28,36.07),NavigationViewModel.parseCoordinate(model.state.value.originText))
                assertFalse(model.state.value.guiding);assertNull(model.state.value.destination)
            }
        }finally{instrumentation.runOnMainSync{store.clear()}}
    }
    @Test fun sharedLocationIntentSelectsPinWithoutStartingGuidanceOrGPS() {
        val context=instrumentation.targetContext
        val cold=Intent(Intent.ACTION_SEND).apply {
            setClass(context,MainActivity::class.java);type="text/plain"
            putExtra(Intent.EXTRA_TEXT,"Public landmark\ngeo:-0.28,36.07?q=-0.28,36.07(Nakuru)")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        ActivityScenario.launch<MainActivity>(cold).use { scenario ->
            lateinit var model:NavigationViewModel
            scenario.onActivity {model=ViewModelProvider(it)[NavigationViewModel::class.java]}
            await("cold shared intent did not select its destination"){model.state.value.destinationLabel=="Nakuru"}
            scenario.onActivity { activity ->
                assertEquals("",model.state.value.originText);assertNull(model.state.value.route)
                assertFalse(model.state.value.gpsWanted);assertFalse(model.state.value.guiding);assertFalse(model.state.value.playback)
                // Same action/type identity keeps ActivityScenario's intent-filtered
                // lifecycle monitor attached while exercising real onNewIntent delivery.
                activity.startActivity(Intent(Intent.ACTION_SEND).apply {
                    setClass(activity,MainActivity::class.java);type="text/plain"
                    putExtra(Intent.EXTRA_TEXT,"geo:-0.72,36.43?q=-0.72,36.43(Naivasha)")
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            await("warm shared intent did not replace the selected destination"){model.state.value.destinationLabel=="Naivasha"}
            scenario.onActivity {
                assertEquals("",model.state.value.originText);assertNull(model.state.value.route)
                assertFalse(model.state.value.gpsWanted);assertFalse(model.state.value.guiding)
                model.acceptSharedText("https://example.com/private-location")
            }
            await("unsupported link did not report its limitation"){model.state.value.searchError!=null}
            scenario.onActivity{assertEquals("Naivasha",model.state.value.destinationLabel)}
        }
    }
}
