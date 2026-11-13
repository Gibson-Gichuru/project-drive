import java.net.URI

plugins {
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val homelabOrigin = "https://drive-api.ripples.boo"
fun serverOrigin(property: String): String {
    val value = providers.gradleProperty(property).getOrElse(homelabOrigin).trimEnd('/')
    val uri = URI(value)
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
        "$property must be an HTTP(S) origin without credentials, path, query or fragment"
    }
    require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")) {
        "$property requires HTTPS, except for loopback development"
    }
    return value
}
val mapOrigin = serverOrigin("mapServerUrl")
val apiOrigin = serverOrigin("apiServerUrl")
val localTestAuth = URI(apiOrigin).host in setOf("127.0.0.1", "localhost")

android {
    namespace = "org.projectdrive.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.projectdrive.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MAP_SERVER_URL", "\"$mapOrigin\"")
        buildConfigField("String", "API_SERVER_URL", "\"$apiOrigin\"")
    }

    buildTypes {
        debug { buildConfigField("boolean", "LOCAL_TEST_AUTH", localTestAuth.toString()) }
        release { buildConfigField("boolean", "LOCAL_TEST_AUTH", "false") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencyLocking { lockAllConfigurations() }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    implementation(libs.maplibre)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
