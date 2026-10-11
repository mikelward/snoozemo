plugins {
    alias(libs.plugins.android.library)
}

// The implementation of `:core`'s PresenceMonitor (SPEC.md §6.1):
// `GeofencePresenceMonitor`, the Geofencing-API monitor the app uses.
// Everything above this module is presence-agnostic — the state machine, the DND
// handling, the tile, and the end-condition sheet are all shared.
//
// The interface itself lives in `:core`, with its consumer: the controller takes
// a PresenceMonitor by injection, so a contract defined here would need `:core`
// to depend on `:presence` while `:presence` depends on `:core` for `Anchor`.

android {
    namespace = "app.snoozemo.presence"
    compileSdk = 37

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // The Geofencing API.
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
