package org.projectdrive.app.feature.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.projectdrive.app.core.auth.*
import org.projectdrive.app.ui.JourneyCard
import javax.inject.Inject

data class AccountUi(val busy:Boolean=false,val error:String?=null)

@HiltViewModel
class AuthViewModel @Inject constructor(private val auth:AuthRepository,private val convoy:ConvoySession):ViewModel() {
    val account=auth.state
    private val mutable=MutableStateFlow(AccountUi());val ui=mutable.asStateFlow()
    init {run {auth.restore()}}
    fun retry()=run {auth.restore()}
    fun google(open:(String)->Unit)=run {open(auth.googleUrl())}
    fun callback(value:String){
        // A cold-start restore may still be running; the repository serializes both.
        mutable.value=mutable.value.copy(error=null)
        viewModelScope.launch {try{auth.callback(value)}catch(e:CancellationException){throw e}
            catch(_:Exception){mutable.value=mutable.value.copy(error="Sign-in did not finish · please try Google again")}}
    }
    fun signOut(){convoy.stopSharing("Sharing off · signing out");run {auth.signOut()}}
    private fun run(block:suspend()->Unit){if(mutable.value.busy)return;mutable.value=mutable.value.copy(busy=true,error=null)
        viewModelScope.launch {try{block()}catch(e:CancellationException){throw e}
            catch(_:Exception){mutable.value=mutable.value.copy(error="Could not finish sign-in · check your connection and retry")}
            finally{mutable.value=mutable.value.copy(busy=false)}}
    }
}

/** Plain content: no account SDK, network, database or location dependencies. */
@Composable
fun AccountContent(account:AuthState,ui:AccountUi,onGoogle:()->Unit,onSignOut:()->Unit,onRetry:()->Unit) {
    JourneyCard {
        Text(if(account.userId==null)"Bring your people along" else "Hello, ${account.name}",style=MaterialTheme.typography.titleLarge)
        Text(account.message,style=MaterialTheme.typography.bodyMedium)
        if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(account.userId!=null) {
            Text("Your account unlocks private convoys. Location sharing and uploads stay under your control.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=onSignOut,enabled=!ui.busy,modifier=Modifier.fillMaxWidth()){Text("Sign out")}
        } else {
            OutlinedButton(onClick=onGoogle,enabled=account.ready&&account.available&&!account.pendingLogout&&!ui.busy,modifier=Modifier.fillMaxWidth()) {
                Text("Continue with Google")
            }
            Text("Sign in or create your account with Google. Maps and your local journal work without an account.",style=MaterialTheme.typography.bodySmall)
        }
        if(!account.ready||!account.available||account.pendingLogout||ui.error!=null) {
            TextButton(onClick=onRetry,enabled=!ui.busy){Text("Retry connection")}
        }
    }
}
