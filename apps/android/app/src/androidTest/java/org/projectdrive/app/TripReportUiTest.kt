package org.projectdrive.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.projectdrive.app.core.telemetry.*
import org.projectdrive.app.feature.telemetry.*
import org.projectdrive.app.ui.DriveTheme
import java.io.File
import kotlin.math.sin

/** Controlled synthetic display data only; no app graph, Room or GPS providers. */
class TripReportUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private fun fixture():TripReportData {
        val summary=TelemetryProcessor(0,1794736800000).result(1_800_000).summary.copy(distanceMeters=18200.0,coveredSeconds=1782.0,
            meanSpeedMps=12.2,maxSpeedMps=25.5,speedSamples=1750,gpsSamples=1770,meanAccuracyMeters=5.0,imuSamples=32000,rotationSamples=28000,acceleration=8,braking=3)
        val points=(0 until 180).filter{it !in 80..84}.map { i ->
            val mean=if(i<3||i in 40..46||i>174)0.0 else (10+6*sin(i*.13)+3*sin(i*.73)).coerceAtLeast(0.0)
            SpeedBucket(i*10,10,mean,if(mean==0.0)0.0 else mean+2+kotlin.math.abs(3*sin(i*.31)))
        }
        return TripReportData(summary.copy(meanSpeedMps=points.sumOf{it.meanMps*it.samples}/points.sumOf{it.samples},maxSpeedMps=points.maxOf{it.maxMps}),points,true,ReportEvidence(80,75,600.0,300.0,0.0))
    }
    private fun capture(name:String) {
        val path=File(rule.activity.filesDir,name)
        path.outputStream().use{rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        assertTrue(path.length()>0)
    }
    @Test fun newReportShowsChartSignalsQualityAndBackAction() {
        var back=0
        rule.setContent{DriveTheme{TripReportContent(fixture(),{back++})}}
        rule.onNodeWithText("Trip details").assertIsDisplayed()
        capture("outset-report-top-test.png")
        rule.onNodeWithText("Driving signals").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Rapid acceleration").assertIsDisplayed()
        rule.onNodeWithText("Speed").performScrollTo()
        rule.onNodeWithContentDescription("Speed history chart",substring=true).performScrollTo().assertIsDisplayed()
        capture("outset-report-speed-test.png")
        rule.onNodeWithText("Recording quality").performScrollTo().performClick()
        rule.onNodeWithText("GPS time coverage · 99.0%").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back to journal").assertIsDisplayed().performClick()
        assertEquals(1,back)
    }
    @Test fun legacyReportExplainsMissingHistoryWithoutDrawingFakeCurve() {
        rule.setContent{DriveTheme{TripReportContent(fixture().copy(speedBuckets=emptyList(),historyRecorded=false),{})}}
        rule.onNodeWithText("Speed history wasn’t saved for this trip").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Speed history chart",substring=true).assertDoesNotExist()
    }
    @Test fun interruptedAndMissingSpeedStatesRemainReadable() {
        val data=fixture().copy(summary=fixture().summary.copy(interrupted=true,speedSamples=0),speedBuckets=emptyList())
        rule.setContent{DriveTheme{TripReportContent(data,{})}}
        rule.onNodeWithText("Interrupted · saved portion only").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Last checkpoint").assertExists()
        rule.onNodeWithText("No reliable speed history").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Speed unavailable · no reliable estimates").performScrollTo().assertIsDisplayed()
    }
    @Test fun largeTextKeepsReportControlsAndChartAccessible() {
        rule.setContent{DriveTheme{CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.3f)){TripReportContent(fixture(),{})}}}
        rule.onNodeWithContentDescription("Back to journal").assertIsDisplayed()
        capture("outset-report-large-text-test.png")
        rule.onNodeWithText("Speed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Speed history chart",substring=true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back to journal").assertIsDisplayed()
    }
}
