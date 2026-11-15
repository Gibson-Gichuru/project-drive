package org.projectdrive.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.projectdrive.app.core.auth.AuthState
import org.projectdrive.app.feature.convoy.*
import org.projectdrive.app.ui.DriveTheme

/** Synthetic UI only: never opens a browser or accesses the owner's account. */
class AuthUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun googleOpensOnlyOnExplicitTapAndNoPasswordFormIsShown(){
        var requests=0
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){AccountContent(AuthState(ready=true,available=true),AccountUi(),{requests++},{},{})}}}
        assertEquals(0,requests)
        rule.onNodeWithText("Continue with Google").performScrollTo().performClick();assertEquals(1,requests)
        rule.onNodeWithText("Email").assertDoesNotExist();rule.onNodeWithText("Password").assertDoesNotExist()
    }
    @Test fun pendingRevocationBlocksNewSignInAndOffersRetry(){
        var retry=false
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){AccountContent(AuthState(ready=true,available=true,pendingLogout=true),AccountUi(),{},{},{retry=true})}}}
        rule.onNodeWithText("Continue with Google").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Retry connection").performScrollTo().performClick();assertTrue(retry)
    }
    @Test fun signedInAccountOffersExplicitSignOutWithoutSharingControl(){
        var signedOut=false
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){AccountContent(AuthState(ready=true,available=true,userId="synthetic",name="Driver"),AccountUi(),{},{signedOut=true},{})}}}
        rule.onNodeWithText("Hello, Driver").assertIsDisplayed()
        rule.onNodeWithText("Share my live location").assertDoesNotExist()
        rule.onNodeWithText("Sign out").performScrollTo().performClick();assertTrue(signedOut)
    }
}
