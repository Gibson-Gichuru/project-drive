package org.projectdrive.app.core.navigation

import org.junit.Assert.*
import org.junit.Test

class MapLinkTest {
    @Test fun explicitPinsAndNamesOpenWithoutProviderSDKs() {
        val links=listOf("geo:-0.28,36.07", "geo:0,0?q=-0.28%2C36.07(Nakuru)","https://www.openstreetmap.org/?mlat=-0.28&mlon=36.07", "https://www.google.com/maps/search/?api=1&query=-0.28%2C36.07", "https://maps.google.com/?q=-0.28,36.07", "https://www.google.com/maps/dir/?api=1&origin=1,2&destination=-0.28,36.07")
        links.forEach { link -> val pin=MapLinks.parse(link) as MapLink.Pin;assertEquals(-0.28,pin.coordinate.latitude,0.00001);assertEquals(36.07,pin.coordinate.longitude,0.00001) }
        assertEquals(MapLink.Search("Nakuru"),MapLinks.parse("geo:0,0?q=Nakuru"))
        assertEquals(MapLink.Search("Nakuru Museum"),MapLinks.parse("https://www.google.com/maps/search/?api=1&query=Nakuru%20Museum"))
    }
    @Test fun destinationDataWinsOverViewportAndSharedTextWorks() {
        val value=MapLinks.parse("Nakuru\nhttps://www.google.com/maps/place/Nakuru/@1,2,12z/data=!3d-0.28!4d36.07") as MapLink.Pin
        assertEquals(-0.28,value.coordinate.latitude,0.00001)
        assertTrue((MapLinks.parse("https://www.openstreetmap.org/#map=12/-0.28/36.07") as MapLink.Pin).label.contains("centre"))
        assertTrue(MapLinks.parse("https://maps.app.goo.gl/example") is MapLink.Short)
    }
    @Test fun arbitraryUrlsAndUnsafeRedirectsAreRejected() {
        listOf("file:///etc/passwd","javascript:alert(1)","https://google.com.evil.test/maps?q=1,2","https://www.google.com@evil.test/maps?q=1,2","https://www.google.com:8443/maps?q=1,2","https://192.168.1.1/maps?q=1,2","geo:91,36", "x".repeat(4097)).forEach { assertEquals(it,MapLink.Unsupported,MapLinks.parse(it)) }
        listOf("http://maps.app.goo.gl/x","https://127.0.0.1/maps","https://example.com/maps","https://goo.gl/unrelated","https://www.google.com/search?q=name").forEach { assertFalse(MapLinks.allowedExpansion(it)) }
        assertTrue(MapLinks.allowedExpansion("https://www.google.com/maps?q=1,2"))
    }
    @Test fun newInstallDoesNotAssumeEitherEndpointOrGPS() {
        val state=org.projectdrive.app.NavigationState()
        assertTrue(state.originText.isEmpty());assertTrue(state.destinationText.isEmpty())
        assertNull(state.destination);assertFalse(state.gpsWanted);assertNull(state.fix)
    }
}
