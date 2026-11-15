package org.projectdrive.app.core.auth

import java.net.URI
import java.net.URLDecoder

/** Only an exact PKCE callback is consumed; shared map links keep their own path. */
object GoogleCallback {
    const val REDIRECT = "org.projectdrive.outset://auth/callback"
    fun matches(value: String): Boolean = try {
        val uri=URI(value)
        uri.scheme=="org.projectdrive.outset" && uri.host=="auth"
    } catch (_: Exception) { false }
    fun code(value: String): String {
        require(value.length<=8192)
        val uri=URI(value)
        require(matches(value) && uri.rawAuthority=="auth" && uri.rawPath=="/callback" && uri.fragment==null)
        val fields=uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
            val parts=it.split('=',limit=2)
            URLDecoder.decode(parts[0],"UTF-8") to URLDecoder.decode(parts.getOrElse(1){""},"UTF-8")
        }
        require(fields.count{it.first=="code"}==1 && fields.none{it.first.startsWith("error")})
        return fields.single{it.first=="code"}.second.also { require(it.length in 1..4096 && it.none(Char::isISOControl)) }
    }
}
