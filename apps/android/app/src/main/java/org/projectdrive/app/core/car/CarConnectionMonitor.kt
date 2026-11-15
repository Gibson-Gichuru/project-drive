package org.projectdrive.app.core.car

import android.content.Context
import androidx.annotation.MainThread
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class CarConnectionState(val label:String) {
    Checking("Checking car connection…"),
    NotDetected("Android Auto not detected"),
    AndroidAuto("Android Auto connected"),
    Automotive("Android Automotive"),
    Unavailable("Car connection unavailable"),
}

/** Advisory connection state only. No GPS, Bluetooth scan, vehicle identity,
 * recording/sharing action or background-start privilege is attached to it. */
@Singleton
class CarConnectionMonitor internal constructor(private val source:()->LiveData<Int>) {
    @Inject constructor(@ApplicationContext context:Context):this({CarConnection(context).type})
    private val mutable=MutableStateFlow(CarConnectionState.Checking)
    val state=mutable.asStateFlow()
    private var observed:LiveData<Int>?=null
    private var monitoring=false
    private val observer=Observer<Int> { type -> mutable.value=when(type) {
        CarConnection.CONNECTION_TYPE_PROJECTION -> CarConnectionState.AndroidAuto
        CarConnection.CONNECTION_TYPE_NATIVE -> CarConnectionState.Automotive
        CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> CarConnectionState.NotDetected
        else -> CarConnectionState.Unavailable
    } }
    @MainThread fun setMonitoring(needed:Boolean) {
        if(needed==monitoring)return
        monitoring=needed
        if(!needed) {
            runCatching{observed?.removeObserver(observer)};observed=null;mutable.value=CarConnectionState.Checking
        } else if(observed==null) {
            mutable.value=CarConnectionState.Checking
            try { val live=source();observed=live;live.observeForever(observer) }
            catch(_:RuntimeException) {runCatching{observed?.removeObserver(observer)};observed=null;mutable.value=CarConnectionState.Unavailable}
        }
    }
}
