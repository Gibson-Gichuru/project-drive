package org.projectdrive.app

import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.auth.GoogleCallback

class GoogleCallbackTest {
    @Test fun pkceCallbackAcceptsOnlyTheExactOriginAndSingleCode() {
        assertEquals("opaque-code",GoogleCallback.code(GoogleCallback.REDIRECT+"?code=opaque-code"))
        listOf("https://auth/callback?code=x","org.projectdrive.outset://attacker/callback?code=x",
            "org.projectdrive.outset://auth@attacker/callback?code=x","org.projectdrive.outset://auth:99/callback?code=x",
            "org.projectdrive.outset://auth/other?code=x",GoogleCallback.REDIRECT+"?code=x&code=y",
            GoogleCallback.REDIRECT+"?code=x#access_token=bad",GoogleCallback.REDIRECT+"?error=denied",
            GoogleCallback.REDIRECT+"?code=%0A").forEach{assertTrue(it,runCatching{GoogleCallback.code(it)}.isFailure)}
    }
}
