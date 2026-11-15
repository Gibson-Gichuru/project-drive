package org.projectdrive.app.map

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.core.navigation.Route
import org.projectdrive.app.core.convoy.Participant

// All MapLibre types stay in this renderer adapter.
class MapLibreRenderer(context: Context, savedState: Bundle?, server: String, onPin: (Coordinate) -> Unit, private val onExplorationChanged: (Boolean) -> Unit = {}) {
    private val mapView: MapView
    private var map: MapLibreMap? = null
    @Suppress("DEPRECATION")
    private val restoredCamera=savedState?.getParcelable<CameraPosition>(MapLibreConstants.STATE_CAMERA_POSITION)
    private fun regionalCamera()=CameraPosition.Builder().target(LatLng(-1.2921,36.8219)).zoom(12.0).build()
    private var route: Route? = null
    private var destination: Coordinate? = null
    private var origin: Coordinate? = null
    private var requestedFocus:Coordinate?=null
    private var position: Coordinate? = null
    private var simulated = false
    private var participants = emptyList<Participant>()
    private var now = 0L
    private var fitted: Route? = null
    private var followedPosition: Coordinate? = null
    private var cameraWasFollowing = false
    private val tracking = CameraTracking(savedState?.getBoolean("drive.camera.exploring") ?: false)
    val exploring get() = tracking.exploring
    val view: View get() = mapView

    init {
        MapLibre.getInstance(context)
        mapView = MapView(context)
        mapView.onCreate(savedState)
        mapView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (!tracking.exploring && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                mapView.post { positionCamera(force = true) }
            }
        }
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val json = context.assets.open(if (night) "kenya-style-night.json" else "kenya-style.json").bufferedReader().use { it.readText() }
            .replace("__BASE_URL__", server.trimEnd('/'))
        mapView.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isAttributionEnabled = true
            // Keep native attribution above the system gesture area on an edge-to-edge map.
            val density = mapView.resources.displayMetrics.density
            ready.uiSettings.isLogoEnabled=false
            ready.uiSettings.setAttributionGravity(android.view.Gravity.BOTTOM or android.view.Gravity.END)
            ready.uiSettings.setAttributionMargins(0,0,(8*density).toInt(),(24*density).toInt())
            ready.uiSettings.setAttributionTintColor(android.graphics.Color.parseColor(if(night)"#d3dce3" else "#242628"))
            ready.uiSettings.setCompassMargins(0,(56*density).toInt(),(16*density).toInt(),0)
            ready.uiSettings.setAllGesturesEnabled(true)
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    tracking.gesture()
                    onExplorationChanged(true)
                }
            }
            ready.addOnMapLongClickListener { point -> onPin(Coordinate(point.latitude, point.longitude)); true }
            ready.setStyle(Style.Builder().fromJson(json)) { style ->
                listOf("route", "origin", "destination", "position", "convoy-fresh", "convoy-stale", "convoy-accuracy").forEach { style.addSource(GeoJsonSource(it, emptyCollection)) }
                style.addLayer(LineLayer("route-casing", "route").withProperties(lineColor(if(night) "#10281f" else "#ffffff"), lineWidth(9f), lineOpacity(0.95f), lineCap("round"), lineJoin("round")))
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor(if(night) "#a8c7fa" else "#3278f6"), lineWidth(5f), lineOpacity(1f), lineCap("round"), lineJoin("round")))
                style.addLayer(CircleLayer("origin-dot","origin").withProperties(circleColor("#205744"),circleRadius(6f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
                style.addLayer(SymbolLayer("origin-label","origin").withProperties(textField("Start"),textFont(arrayOf("Noto Sans Regular")),textSize(12f),textColor("#205744"),textHaloColor("#ffffff"),textHaloWidth(2f),textOffset(arrayOf(0f,-1.3f))))
                style.addLayer(CircleLayer("destination-dot", "destination").withProperties(circleColor("#c15c45"), circleRadius(7f), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
                style.addImage("vehicle-live",vehicleBadge(android.graphics.Color.parseColor("#242628")))
                style.addImage("vehicle-preview",vehicleBadge(android.graphics.Color.parseColor("#65479c")))
                style.addLayer(SymbolLayer("position-badge", "position").withProperties(iconImage("vehicle-live"),iconAnchor("bottom"),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(FillLayer("convoy-accuracy-area", "convoy-accuracy").withProperties(fillColor("#087f5b"), fillOpacity(0.12f)))
                style.addLayer(CircleLayer("convoy-fresh-dot", "convoy-fresh").withProperties(circleColor("#087f5b"), circleRadius(10f), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                style.addLayer(CircleLayer("convoy-stale-dot", "convoy-stale").withProperties(circleColor("#666666"), circleRadius(10f), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                for (source in listOf("convoy-fresh", "convoy-stale")) style.addLayer(SymbolLayer("$source-label", source).withProperties(textField("{label}"), textFont(arrayOf("Noto Sans Regular")), textSize(12f), textColor("#202020"), textHaloColor("#ffffff"), textHaloWidth(2f), textOffset(arrayOf(0f, 1.5f))))
                ready.moveCamera(CameraUpdateFactory.newCameraPosition(restoredCamera ?: regionalCamera()))
                redraw()
                mapView.post { positionCamera() }
            }
        }
    }

    fun render(route: Route?, destination: Coordinate?, position: Coordinate?, simulated: Boolean, follow: Boolean, participants: List<Participant> = emptyList(), now: Long = 0, origin:Coordinate?=null) {
        this.origin=origin
        this.participants = participants; this.now = now
        this.route = route
        this.destination = destination
        this.position = position
        this.simulated = simulated
        tracking.update(route, follow)
        onExplorationChanged(tracking.exploring)
        redraw()
        positionCamera()
    }

    private fun redraw() {
        val style = map?.style ?: return
        for ((source, stale) in listOf("convoy-fresh" to false, "convoy-stale" to true)) {
            val markers = participants.filter { !it.expired(now) && it.stale(now) == stale }
            val features = JSONArray().also { list -> markers.forEach { list.put(JSONObject(point(it.coordinate)).put("properties", JSONObject().put("label", "${it.id.take(6)}${if (stale) " · stale" else ""}"))) } }
            style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
        }
        val areas = JSONArray().also { list -> participants.filter { !it.expired(now) }.forEach { list.put(accuracyArea(it)) } }
        style.getSourceAs<GeoJsonSource>("convoy-accuracy")?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", areas).toString())
        style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(route?.let { line(it.geometry) } ?: emptyCollection)
        style.getSourceAs<GeoJsonSource>("origin")?.setGeoJson(origin?.let { point(it) } ?: emptyCollection)
        style.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(destination?.let { point(it) } ?: emptyCollection)
        style.getSourceAs<GeoJsonSource>("position")?.setGeoJson(position?.let { point(it) } ?: emptyCollection)
        style.getLayerAs<SymbolLayer>("position-badge")?.setProperties(iconImage(if(simulated)"vehicle-preview" else "vehicle-live"))
    }

    fun focus(coordinate:Coordinate) {
        requestedFocus=coordinate
        tracking.gesture();onExplorationChanged(true)
        positionCamera()
    }

    fun recenter() {
        tracking.recenter()
        onExplorationChanged(false)
        positionCamera(force = true, fallback = true)
    }

    private fun positionCamera(force: Boolean = false, fallback: Boolean = false) {
        val ready = map ?: return
        if (ready.style == null || mapView.width == 0 || mapView.height == 0) return
        requestedFocus?.let { point ->
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude,point.longitude),14.0))
            requestedFocus=null
        }
        if(tracking.exploring)return
        val currentPosition = position
        if (tracking.following && currentPosition == null && !force) return
        if (tracking.following && currentPosition != null) {
            if (force || !cameraWasFollowing || followedPosition != currentPosition) {
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(currentPosition.latitude, currentPosition.longitude), 16.0))
                followedPosition = currentPosition
            }
            cameraWasFollowing = true
        } else {
            val wasFollowing = cameraWasFollowing
            cameraWasFollowing = false
            route?.let { current ->
                if (force || fitted !== current || wasFollowing) {
                    val bounds = LatLngBounds.Builder().includes(current.geometry.map { LatLng(it.latitude, it.longitude) }).build()
                    val density=mapView.resources.displayMetrics.density
                    val landscape=mapView.width>mapView.height
                    val side=(24*density).toInt().coerceAtMost(mapView.width/6)
                    val top=(80*density).toInt().coerceAtMost(mapView.height/4)
                    val textGrowth=(mapView.resources.configuration.fontScale-1f).coerceAtLeast(0f)
                    val bottom=((if(landscape)150f else 260f+180f*textGrowth)*density).toInt().coerceAtMost(mapView.height*45/100)
                    ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,side,top,side,bottom))
                    fitted = current
                }
                return
            }
            if (fallback) ready.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPosition?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(-1.2921, 36.8219), if (currentPosition != null) 16.0 else 12.0))
        }
    }

    /** Original vehicle pin, without a fabricated profile photograph or heading. */
    private fun vehicleBadge(color:Int):android.graphics.Bitmap {
        val bitmap=android.graphics.Bitmap.createBitmap(56,66,android.graphics.Bitmap.Config.ARGB_8888)
        // The style image uses logical pixels; keep its size consistent across densities.
        bitmap.density=android.util.DisplayMetrics.DENSITY_DEFAULT
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color=android.graphics.Color.argb(65,0,0,0);canvas.drawCircle(28f,30f,27f,paint)
        paint.color=color
        val tip=android.graphics.Path().apply{moveTo(16f,44f);lineTo(28f,65f);lineTo(40f,44f);close()}
        canvas.drawPath(tip,paint);canvas.drawCircle(28f,28f,25f,paint)
        paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=1.5f
        paint.color=android.graphics.Color.parseColor("#747b80");canvas.drawCircle(28f,28f,21f,paint)
        paint.strokeWidth=2f;paint.strokeCap=android.graphics.Paint.Cap.ROUND;paint.strokeJoin=android.graphics.Paint.Join.ROUND
        paint.color=android.graphics.Color.WHITE
        val car=android.graphics.Path().apply{
            moveTo(15f,35f);lineTo(15f,26f);lineTo(19f,19f);lineTo(37f,19f);lineTo(41f,26f);lineTo(41f,35f);close()
            moveTo(15f,26f);lineTo(41f,26f);moveTo(19f,35f);lineTo(19f,39f);moveTo(37f,35f);lineTo(37f,39f)
            moveTo(20f,30f);lineTo(22f,30f);moveTo(34f,30f);lineTo(36f,30f)
        }
        canvas.drawPath(car,paint)
        return bitmap
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()
    fun saveState(bundle: Bundle) {
        mapView.onSaveInstanceState(bundle)
        // A second configuration change can arrive before the replacement map loads.
        // Preserve the last real viewport instead of saving the SDK's temporary world view.
        val camera=map?.takeIf {it.style?.isFullyLoaded==true}?.cameraPosition ?: restoredCamera ?: regionalCamera()
        val point=camera.target
        val snapshot=CameraPosition.Builder(camera).target(point?.let {LatLng(it.latitude,it.longitude)}).build()
        bundle.putParcelable(MapLibreConstants.STATE_CAMERA_POSITION,snapshot)
        bundle.putBoolean("drive.camera.exploring", tracking.exploring)
    }

    private fun accuracyArea(marker: Participant): JSONObject {
        val lat = Math.toRadians(marker.coordinate.latitude); val lon = Math.toRadians(marker.coordinate.longitude)
        val radius = marker.accuracy / 6371000.0
        val ring = JSONArray()
        for (step in 0..48) {
            val bearing = step * 2 * Math.PI / 48
            val y = kotlin.math.asin(kotlin.math.sin(lat) * kotlin.math.cos(radius) + kotlin.math.cos(lat) * kotlin.math.sin(radius) * kotlin.math.cos(bearing))
            val x = lon + kotlin.math.atan2(kotlin.math.sin(bearing) * kotlin.math.sin(radius) * kotlin.math.cos(lat), kotlin.math.cos(radius) - kotlin.math.sin(lat) * kotlin.math.sin(y))
            ring.put(JSONArray().put((Math.toDegrees(x) + 540) % 360 - 180).put(Math.toDegrees(y)))
        }
        return JSONObject(feature(JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))))
    }
    private fun point(p: Coordinate) = feature(JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.longitude).put(p.latitude)))
    private fun line(points: List<Coordinate>) = feature(JSONObject().put("type", "LineString").put("coordinates", JSONArray().also { list -> points.forEach { list.put(JSONArray().put(it.longitude).put(it.latitude)) } }))
    private fun feature(geometry: JSONObject) = JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", JSONObject()).toString()
    private val emptyCollection get() = "{\"type\":\"FeatureCollection\",\"features\":[]}"
}
