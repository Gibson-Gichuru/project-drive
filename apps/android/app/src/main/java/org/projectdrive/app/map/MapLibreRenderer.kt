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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.projectdrive.app.core.navigation.Coordinate
import org.projectdrive.app.core.navigation.Route

// All MapLibre types stay in this renderer adapter.
class MapLibreRenderer(context: Context, savedState: Bundle?, server: String, onPin: (Coordinate) -> Unit) {
    private val mapView: MapView
    private var map: MapLibreMap? = null
    private var route: Route? = null
    private var destination: Coordinate? = null
    private var position: Coordinate? = null
    private var simulated = false
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
                listOf("route", "destination", "position").forEach { style.addSource(GeoJsonSource(it, emptyCollection)) }
                style.addLayer(LineLayer("route-line", "route").withProperties(lineColor("#11786e"), lineWidth(6f), lineOpacity(0.9f)))
                style.addLayer(CircleLayer("destination-dot", "destination").withProperties(circleColor("#c15c45"), circleRadius(7f), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("position-dot", "position").withProperties(circleColor("#2468c6"), circleRadius(9f), circleStrokeColor("#ffffff"), circleStrokeWidth(3f)))
                if (savedState == null) ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-1.2921, 36.8219), 12.0))
                redraw()
            }
        }
    }

    fun render(route: Route?, destination: Coordinate, position: Coordinate?, simulated: Boolean, follow: Boolean) {
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

    private fun point(p: Coordinate) = feature(JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.longitude).put(p.latitude)))
    private fun line(points: List<Coordinate>) = feature(JSONObject().put("type", "LineString").put("coordinates", JSONArray().also { list -> points.forEach { list.put(JSONArray().put(it.longitude).put(it.latitude)) } }))
    private fun feature(geometry: JSONObject) = JSONObject().put("type", "Feature").put("geometry", geometry).put("properties", JSONObject()).toString()
    private val emptyCollection get() = "{\"type\":\"FeatureCollection\",\"features\":[]}"
}
