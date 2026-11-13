package org.projectdrive.app.core.navigation

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RemoteNavigationEngine(private val server: String) : NavigationEngine {
    override suspend fun route(origin: Coordinate, destination: Coordinate): Route = withContext(Dispatchers.IO) {
        val connection = URL("${server.trimEnd('/')}/v1/navigation/route").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val request = JSONObject()
                .put("origin", origin.json())
                .put("destination", destination.json())
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            when (connection.responseCode) {
                200 -> Unit
                422 -> error("No drivable route in the Kenya data. Choose another destination.")
                else -> error("Map server is unavailable. Check your connection and try again.")
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 8 * 1024 * 1024) { "Route is too large." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            require(bytes.size <= 8 * 1024 * 1024) { "Route is too large." }
            val response = JSONObject(String(bytes, Charsets.UTF_8))
            val points = response.getJSONArray("geometry")
            val instructions = response.getJSONArray("maneuvers")
            Route(
                geometry = List(points.length()) { i ->
                    points.getJSONObject(i).let { Coordinate(it.getDouble("latitude"), it.getDouble("longitude")) }
                },
                distanceMeters = response.getDouble("distance_m"),
                durationSeconds = response.getDouble("duration_s"),
                maneuvers = List(instructions.length()) { i ->
                    instructions.getJSONObject(i).let {
                        Maneuver(it.getString("instruction"), it.getInt("start_index"), it.getInt("end_index"), it.getDouble("distance_m"), it.getDouble("duration_s"))
                    }
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun Coordinate.json() = JSONObject().put("latitude", latitude).put("longitude", longitude)
}
