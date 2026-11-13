package org.projectdrive.app.core.navigation

import java.net.URI
import java.net.URLDecoder

sealed interface MapLink {
    data class Pin(val coordinate:Coordinate,val label:String):MapLink
    data class Search(val query:String):MapLink
    data class Short(val url:String):MapLink
    data object Unsupported:MapLink
}

/** Parses destination intent, never uses an unrelated map viewport over an explicit pin. */
object MapLinks {
    private val google=setOf("google.com","www.google.com","maps.google.com","maps.google.co.ke","www.google.co.ke")
    private val osm=setOf("openstreetmap.org","www.openstreetmap.org","osm.org","www.osm.org")
    private val short=setOf("maps.app.goo.gl","goo.gl")
    private val pair=Regex("^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)(?:\\s*\\(([^)]{1,160})\\))?\\s*$")
    private fun coordinate(text:String,label:String="Shared location"):MapLink.Pin? {
        val m=pair.matchEntire(text)?:return null
        val lat=m.groupValues[1].toDoubleOrNull()?:return null;val lon=m.groupValues[2].toDoubleOrNull()?:return null
        if(!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0||lon !in -180.0..180.0)return null
        return MapLink.Pin(Coordinate(lat,lon),m.groupValues[3].ifBlank{label})
    }
    private fun decode(s:String)=URLDecoder.decode(s,"UTF-8")
    fun parse(sharedText:String):MapLink { return try {
        if(sharedText.length>4096) return MapLink.Unsupported
        val text=sharedText.trim()
        val raw=Regex("(?:https?://|geo:)[^\\s<>]+").find(text)?.value?:return MapLink.Unsupported
        val uri=URI(raw);val params=uri.rawQuery.orEmpty().split('&').filter{it.isNotBlank()}.associate { part -> val p=part.split('=',limit=2);decode(p[0]) to decode(p.getOrElse(1){""}) }
        if(uri.scheme=="geo") {
            val content=uri.rawSchemeSpecificPart.substringBefore('?')
            val q=uri.rawSchemeSpecificPart.substringAfter("?","").split('&').firstOrNull{it.startsWith("q=")}?.substringAfter('=')?.let(::decode)
            if(q!=null) coordinate(q)?:q.takeIf{it.length in 2..120}?.let { MapLink.Search(it) }?:MapLink.Unsupported
            else coordinate(content.substringBefore(';'))?:MapLink.Unsupported
        }else {
            val host=uri.host?.lowercase()?:return MapLink.Unsupported
            if(uri.userInfo!=null||uri.port !in listOf(-1,443)||uri.scheme !in setOf("http","https"))return MapLink.Unsupported
            if(host in short && (host!="goo.gl"||uri.path.startsWith("/maps/"))) return MapLink.Short(raw.replaceFirst("http:","https:").substringBefore('#'))
            if(host in osm && uri.path.startsWith("/go/"))return MapLink.Short(raw.replaceFirst("http:","https:").substringBefore('#'))
            if(host in osm) {
                val lat=params["mlat"]?.toDoubleOrNull();val lon=params["mlon"]?.toDoubleOrNull()
                if(lat!=null&&lon!=null)coordinate("$lat,$lon")?:MapLink.Unsupported
                else if(params["query"]!=null) MapLink.Search(checkNotNull(params["query"]).take(120))
                else {
                    val center=uri.fragment.orEmpty().substringAfter("map=","").substringBefore('&').split('/')
                    if(center.size==3)coordinate("${center[1]},${center[2]}","Shared map centre · check pin")?:MapLink.Unsupported else MapLink.Unsupported
                }
            } else if(host in google && (host.startsWith("maps.")||uri.path.startsWith("/maps"))) {
                val query=params["destination"]?:params["daddr"]?:params["query"]?:params["q"]
                if(query!=null) coordinate(query)?:query.takeIf{it.length in 2..120&&!it.startsWith("place_id:")}?.let { MapLink.Search(it) }?:MapLink.Unsupported
                else {
                    val data=Regex("!3d([+-]?[0-9.]+)!4d([+-]?[0-9.]+)").findAll(raw).lastOrNull()
                    if(data!=null)coordinate("${data.groupValues[1]},${data.groupValues[2]}")?:MapLink.Unsupported
                    else {
                        val name=uri.path.substringAfter("/place/","").substringBefore('/')
                        if(name.isNotBlank()) MapLink.Search(decode(name).take(120))
                        else {
                            val center=Regex("@([+-]?[0-9.]+),([+-]?[0-9.]+)").find(raw)
                            if(center!=null)coordinate("${center.groupValues[1]},${center.groupValues[2]}","Shared map centre · check pin")?:MapLink.Unsupported else MapLink.Unsupported
                        }
                    }
                }
            }else MapLink.Unsupported
        }
    } catch(_:Exception){MapLink.Unsupported}
    }
    fun allowedExpansion(url:String):Boolean=try {
        val uri=URI(url);uri.scheme=="https"&&uri.userInfo==null&&uri.port in listOf(-1,443)&&((uri.host in short&&(uri.host!="goo.gl"||uri.path.startsWith("/maps/")))||(uri.host in google&&(uri.host.startsWith("maps.")||uri.path.startsWith("/maps")))||(uri.host in osm&&(uri.path=="/"||uri.path.startsWith("/go/"))))
    }catch(_:Exception){false}
}
