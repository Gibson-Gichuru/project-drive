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
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.core.reports.*
import org.projectdrive.app.feature.reports.*
import org.projectdrive.app.ui.DriveTheme

/** Isolated UI only. Does not access the owner's account, location or server. */
class ReportsUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private fun show(state:ReportsUi,onEnable:()->Unit={},onPublish:()->Unit={},onVote:(String,Int)->Unit={_,_->}) {
        rule.setContent{DriveTheme{Column(Modifier.verticalScroll(rememberScrollState())){
            ReportsContent(state,onEnable,{},{},{},{},{},{},onPublish,onVote,{},{})
        }}}
    }
    @Test fun nearbyLookupRequiresExplicitConsent(){var enabled=0
        show(ReportsUi(signedIn=true,freshLocation=true),onEnable={enabled++})
        assertEquals(0,enabled)
        rule.onNodeWithText("Show nearby road reports").performScrollTo().performClick();assertEquals(1,enabled)
    }
    @Test fun publishingRequiresPinAndParkedConfirmation(){var published=0
        show(ReportsUi(signedIn=true,pin=Coordinate(0.0,36.0),parked=false),onPublish={published++})
        rule.onNodeWithText("Publish community report").performScrollTo().assertIsNotEnabled();assertEquals(0,published)
    }
    @Test fun movingDisablesVotesAndPublishing(){var votes=0
        show(ReportsUi(signedIn=true,enabled=true,moving=true,pin=Coordinate(0.0,36.0),parked=true,
            reports=listOf(RoadReport("synthetic",ReportCategory.Obstacle,Coordinate(0.0,36.0),"Synthetic",0,Long.MAX_VALUE))),onVote={_,_->votes++})
        rule.onNodeWithText("Publish community report").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Still there").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Not there").performScrollTo().assertIsNotEnabled();assertEquals(0,votes)
    }
    @Test fun signedOutUsersCannotPublishOrQuery(){show(ReportsUi())
        rule.onNodeWithText("Show nearby road reports").assertDoesNotExist()
        rule.onNodeWithText("Publish community report").assertDoesNotExist()
    }
}
