plugins {
    alias(libs.plugins.android.library)
}

// The implementation of `:core`'s PresenceMonitor (SPEC.md §6.1):
// `GeofencePresenceMonitor`, the Geofencing-API monitor the `play` build uses.
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

    // The same distribution dimension `:app` declares, so the app links the
    // matching variant of this library. `play` is the only flavor since the
    // `direct` sideload build was retired (SPEC.md §3); the dimension stays until
    // the flavor concept is removed across both modules (TODO.md). The geofence
    // implementation lives in `src/play/kotlin/…/geofence/`, its Play Services
    // dependency scoped `playImplementation` below.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
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
    // The Geofencing API. `playImplementation` still, since `play` remains a
    // flavor until the flavor concept is removed (TODO.md) — and it is the only
    // flavor, so it reaches every variant.
    "playImplementation"(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
