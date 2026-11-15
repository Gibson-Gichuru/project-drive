package org.projectdrive.app.feature.convoy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

enum class AccountMode { SignIn, SignUp, Recover, VerifySignup, VerifyRecovery, NewPassword }
data class AccountUi(val mode:AccountMode=AccountMode.SignIn,val busy:Boolean=false,val error:String?=null)

@HiltViewModel
class AuthViewModel @Inject constructor(private val auth:AuthRepository,private val convoy:ConvoySession):ViewModel() {
    val account=auth.state
    private val mutable=MutableStateFlow(AccountUi());val ui=mutable.asStateFlow()
    init {run {auth.restore()}}
    fun mode(value:AccountMode){if(!mutable.value.busy)mutable.value=AccountUi(mode=value)}
    fun retry()=run {auth.restore()}
    fun submit(email:String,password:String,name:String,code:String) {
        val mode=mutable.value.mode
        run {
            when(mode) {
                AccountMode.SignIn -> auth.signIn(email,password)
                AccountMode.SignUp -> {auth.signUp(email,password,name);mutable.value=mutable.value.copy(mode=AccountMode.VerifySignup)}
                AccountMode.Recover -> {auth.recover(email);mutable.value=mutable.value.copy(mode=AccountMode.VerifyRecovery)}
                AccountMode.VerifySignup -> auth.verify(email,code,false)
                AccountMode.VerifyRecovery -> {auth.verify(email,code,true);mutable.value=mutable.value.copy(mode=AccountMode.NewPassword)}
                AccountMode.NewPassword -> {auth.password(password);mutable.value=mutable.value.copy(mode=AccountMode.SignIn)}
            }
        }
    }
    fun signOut(){convoy.stopSharing("Sharing off · signing out");run {auth.signOut();mutable.value=mutable.value.copy(mode=AccountMode.SignIn)}}
    private fun run(block:suspend()->Unit){if(mutable.value.busy)return;mutable.value=mutable.value.copy(busy=true,error=null)
        viewModelScope.launch {try{block()}catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(error=when {
                e is AuthFailure && e.status==429 -> "Too many attempts · wait a minute and try again"
                e is AuthFailure && e.status in setOf(400,401,403) -> "Check your email, password or verification code"
                else -> "Could not reach sign-in · check your connection and retry"
            })}finally{mutable.value=mutable.value.copy(busy=false)}}
    }
}

/** Plain content: no network, database, location source, or ViewModel dependency. */
@Composable
fun AccountContent(account:AuthState,ui:AccountUi,onMode:(AccountMode)->Unit,onSubmit:(String,String,String,String)->Unit,onSignOut:()->Unit,onRetry:()->Unit) {
    var email by remember {mutableStateOf("")}
    var name by remember {mutableStateOf("")}
    // Passwords and one-time codes are deliberately absent from saved instance state.
    var password by remember(ui.mode) {mutableStateOf("")}
    var code by remember(ui.mode) {mutableStateOf("")}
    val signing=account.userId==null
    JourneyCard {
        Text(if(signing)"Bring your people along" else "Hello, ${account.name}",style=MaterialTheme.typography.titleLarge)
        Text(account.message,style=MaterialTheme.typography.bodyMedium)
        if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(!account.ready||!account.available||account.pendingLogout){OutlinedButton(onClick=onRetry,enabled=!ui.busy){Text("Retry connection")}}
        if(!signing && ui.mode!=AccountMode.NewPassword) {
            Text("Your account unlocks private convoys. Location sharing and uploads stay under your control.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=onSignOut,enabled=!ui.busy,modifier=Modifier.fillMaxWidth()){Text("Sign out")}
            TextButton(onClick={onMode(AccountMode.NewPassword)},enabled=!ui.busy){Text("Set a new password")}
        } else if(account.ready&&account.available&&!account.pendingLogout) {
            if(ui.mode!=AccountMode.NewPassword)OutlinedTextField(email,{email=it.take(254)},label={Text("Email")},singleLine=true,enabled=!ui.busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
            if(ui.mode==AccountMode.SignUp)OutlinedTextField(name,{name=it.take(80)},label={Text("Display name")},singleLine=true,enabled=!ui.busy,modifier=Modifier.fillMaxWidth())
            if(ui.mode in setOf(AccountMode.SignIn,AccountMode.SignUp,AccountMode.NewPassword))OutlinedTextField(password,{password=it.take(1024)},
                label={Text(if(ui.mode==AccountMode.NewPassword)"New password" else "Password")},supportingText={if(ui.mode!=AccountMode.SignIn)Text("At least 12 characters")},
                visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,enabled=!ui.busy,modifier=Modifier.fillMaxWidth())
            if(ui.mode in setOf(AccountMode.VerifySignup,AccountMode.VerifyRecovery))OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("Email verification code")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,enabled=!ui.busy,modifier=Modifier.fillMaxWidth())
            Button(onClick={onSubmit(email,password,name,code);password="";code=""},enabled=!ui.busy&&when(ui.mode){AccountMode.SignIn->email.isNotBlank()&&password.isNotEmpty();AccountMode.SignUp->email.isNotBlank()&&password.length>=12&&name.isNotBlank();AccountMode.Recover->email.isNotBlank();AccountMode.VerifySignup,AccountMode.VerifyRecovery->email.isNotBlank()&&code.length==6;AccountMode.NewPassword->password.length>=12},modifier=Modifier.fillMaxWidth()) {
                Text(when(ui.mode){AccountMode.SignIn->"Sign in";AccountMode.SignUp->"Create account";AccountMode.Recover->"Send recovery code";AccountMode.VerifySignup,AccountMode.VerifyRecovery->"Verify email";AccountMode.NewPassword->"Save password"})
            }
            if(account.emailEnabled&&ui.mode==AccountMode.SignIn){TextButton(onClick={onMode(AccountMode.SignUp)},enabled=!ui.busy){Text("Create an account")};TextButton(onClick={onMode(AccountMode.Recover)},enabled=!ui.busy){Text("Forgot password?")};TextButton(onClick={onMode(AccountMode.VerifySignup)},enabled=!ui.busy){Text("Enter verification code")}}
            if(account.emailEnabled&&ui.mode==AccountMode.Recover)TextButton(onClick={onMode(AccountMode.VerifyRecovery)},enabled=!ui.busy){Text("I have a recovery code")}
            if(ui.mode!=AccountMode.SignIn&&ui.mode!=AccountMode.NewPassword)TextButton(onClick={onMode(AccountMode.SignIn)},enabled=!ui.busy){Text("Back to sign in")}
            if(!account.emailEnabled)Text("Account registration and password recovery will open when email delivery is ready.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
