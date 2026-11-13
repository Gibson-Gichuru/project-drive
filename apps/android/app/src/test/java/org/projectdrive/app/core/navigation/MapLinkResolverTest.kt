package org.projectdrive.app.core.navigation

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MapLinkResolverTest {
    @Test fun shortMapRedirectSelectsItsExplicitPinWithoutAnotherRequest()=runBlocking {
        var calls=0
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location","https://www.google.com/maps?q=-0.28,36.07").body("".toResponseBody()).build()
        }.build()
        try {
            val result=MapLinkResolver(client).resolve("https://maps.app.goo.gl/public-fixture") as MapLink.Pin
            assertEquals(Coordinate(-0.28,36.07),result.coordinate);assertEquals(1,calls)
        }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun unsafeRedirectAndRedirectLoopCannotFetchArbitraryUrls()=runBlocking {
        for(location in listOf("https://127.0.0.1/private","https://evil.test/location","https://maps.app.goo.gl/loop")) {
            var calls=0
            val client=OkHttpClient.Builder().addInterceptor {chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location",location).body("".toResponseBody()).build()
            }.build()
            try {
                assertEquals(MapLink.Unsupported,MapLinkResolver(client).resolve("https://maps.app.goo.gl/public-fixture"))
                assertEquals(if(location.endsWith("/loop"))5 else 1,calls)
            }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
        }
    }
}
