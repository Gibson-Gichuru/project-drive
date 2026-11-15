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

/** Synthetic content fixtures; no production account, network, Room or GPS. */
class AuthUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun signInRequiresInputAndClearsPasswordAfterSubmission(){
        var submitted=false
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){AccountContent(AuthState(ready=true,available=true),AccountUi(),{}, {email,password,_,_->submitted=email=="driver@example.test"&&password=="synthetic-password"},{},{})}}}
        rule.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Email").performTextInput("driver@example.test")
        rule.onNodeWithText("Password").performTextInput("synthetic-password")
        rule.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
        assertTrue(submitted)
        rule.onNode(hasText("Sign in") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Create an account").assertDoesNotExist()
    }
    @Test fun verificationAndRecoveryOnlyAppearWhenEmailReady(){
        var mode:AccountMode?=null
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){AccountContent(AuthState(ready=true,available=true,emailEnabled=true),AccountUi(),{mode=it},{_,_,_,_->},{},{})}}}
        rule.onNodeWithText("Forgot password?").performScrollTo().performClick()
        assertEquals(AccountMode.Recover,mode)
        rule.onNodeWithText("Enter verification code").performScrollTo().performClick()
        assertEquals(AccountMode.VerifySignup,mode)
        rule.onNodeWithText("Create an account").assertExists()
    }
    @Test fun signedInAccountOffersExplicitSignOutWithoutSharingControl(){
        var signedOut=false
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){AccountContent(AuthState(ready=true,available=true,userId="synthetic",name="Driver"),AccountUi(),{},{_,_,_,_->},{signedOut=true},{})}}}
        rule.onNodeWithText("Hello, Driver").assertIsDisplayed()
        rule.onNodeWithText("Share my live location").assertDoesNotExist()
        rule.onNodeWithText("Sign out").performScrollTo().performClick();assertTrue(signedOut)
    }
}
