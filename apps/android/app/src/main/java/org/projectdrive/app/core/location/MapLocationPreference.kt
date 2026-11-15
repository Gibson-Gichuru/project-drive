package org.projectdrive.app.core.location

import android.content.Context

/** Only a map-display choice is persisted, never a position or permission. */
class MapLocationPreference(context:Context) {
    private val preferences=context.getSharedPreferences("map_location",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=preferences.getBoolean("enabled",true)
        set(value){preferences.edit().putBoolean("enabled",value).apply()}
}
