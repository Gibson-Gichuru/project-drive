package org.projectdrive.app.map

import android.content.Context
import android.os.Bundle
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
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
class MapLibreRenderer(context: Context, savedState: Bundle?, server: String, onPin: (Coordinate) -> Unit) {
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var route: Route? = null
    private var destination: Coordinate? = null
    private var position: Coordinate? = null
    private var simulated = false
    private var participants = emptyList<Participant>()
    private var now = 0L
    private var fitted: Route? = null
    private var following = false
    val view: View get() = mapView

    init {
        MapLibre.getInstance(context)
        mapView = MapView(context)
        mapView.onCreate(savedState)
        mapView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (!following && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                route?.let { current -> mapView.post { fit(current) } }
            }
        }
        val json = context.assets.open("kenya-style.json").bufferedReader().use { it.readText() }
            .replace("__BASE_URL__", server.trimEnd('/'))
        mapView.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isAttributionEnabled = true
            ready.addOnMapLongClickListener { point -> onPin(Coordinate(point.latitude, point.longitude)); true }
            ready.setStyle(Style.Builder().fromJson(json)) { style ->
                listOf("route", "destination", "position", "convoy-fresh", "convoy-stale", "convoy-accuracy").forEach { style.addSource(GeoJsonSource(it, emptyCollection)) }
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#11786e"), lineWidth(6f), lineOpacity(0.9f)))
                style.addLayer(CircleLayer("destination-dot", "destination").withProperties(circleColor("#c15c45"), circleRadius(7f), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("position-dot", "position").withProperties(circleColor("#2468c6"), circleRadius(9f), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                style.addLayer(FillLayer("convoy-accuracy-area", "convoy-accuracy").withProperties(fillColor("#087f5b"), fillOpacity(0.12f)))
                style.addLayer(CircleLayer("convoy-fresh-dot", "convoy-fresh").withProperties(circleColor("#087f5b"), circleRadius(10f), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                style.addLayer(CircleLayer("convoy-stale-dot", "convoy-stale").withProperties(circleColor("#666666"), circleRadius(10f), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                for (source in listOf("convoy-fresh", "convoy-stale")) style.addLayer(SymbolLayer("$source-label", source).withProperties(textField("{label}"), textFont(arrayOf("Noto Sans Regular")), textSize(12f), textColor("#202020"), textHaloColor("#ffffff"), textHaloWidth(2f), textOffset(arrayOf(0f, 1.5f))))
                if (savedState == null) ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-1.2921, 36.8219), 12.0))
                redraw()
            }
        }
    }

    fun render(route: Route?, destination: Coordinate, position: Coordinate?, simulated: Boolean, follow: Boolean, participants: List<Participant> = emptyList(), now: Long = 0) {
        this.participants = participants; this.now = now
        this.route = route
        this.destination = destination
        this.position = position
        this.simulated = simulated
        following = follow
        redraw()
        if (follow && position != null) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.latitude, position.longitude), 16.0))
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
        style.getSourceAs<GeoJsonSource>("destination")?.setGeoJson(destination?.let { point(it) } ?: emptyCollection)
        style.getSourceAs<GeoJsonSource>("position")?.setGeoJson(position?.let { point(it) } ?: emptyCollection)
        style.getLayerAs<CircleLayer>("position-dot")?.setProperties(circleColor(if (simulated) "#8e4ab8" else "#2468c6"))
        route?.let { current ->
            if (fitted !== current) {
                mapView.post { if (!following) fit(current) }
                fitted = current
            }
        }
    }

    private fun fit(current: Route) {
        if (route !== current || following || mapView.width == 0 || mapView.height == 0) return
        val bounds = LatLngBounds.Builder().includes(current.geometry.map { LatLng(it.latitude, it.longitude) }).build()
        map?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 90))
    }

    fun onStart() = mapView.onStart()
    fun onResume() = mapView.onResume()
    fun onPause() = mapView.onPause()
    fun onStop() = mapView.onStop()
    fun onDestroy() = mapView.onDestroy()
    fun onLowMemory() = mapView.onLowMemory()
    fun saveState(bundle: Bundle) = mapView.onSaveInstanceState(bundle)

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
