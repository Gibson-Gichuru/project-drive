plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        buildConfigField("String", "MAP_SERVER_URL", "\"${providers.gradleProperty("mapServerUrl").getOrElse("http://127.0.0.1:18081")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencyLocking { lockAllConfigurations() }

dependencies {
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
