plugins {
    alias(libs.plugins.android.library)
}

// The implementations of `:core`'s PresenceMonitor (SPEC.md §6.1):
// `GeofencePresenceMonitor` for the `play` flavor and `ForegroundPresenceMonitor`
// for `direct`. Everything above this module is flavor-agnostic — the state
// machine, the DND handling, the tile, and the end-condition sheet are all
// shared, and neither flavor knows about the other.
//
// The interface itself lives in `:core`, with its consumer: the controller takes
// a PresenceMonitor by injection, so a contract defined here would need `:core`
// to depend on `:presence` while `:presence` depends on `:core` for `Anchor`.
//
// Empty until Phase 3 adds the geofence implementation and the Play Services
// Location dependency it needs; Phase 7 adds the foreground-service one.

android {
    namespace = "app.snoozemo.presence"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
    }

    // The same distribution dimension `:app` declares, so each APK links the
    // matching variant of this library rather than both consuming one unflavored
    // AAR. Without it, Phase 3's Play Services / geofencing pieces and Phase 7's
    // foreground service would ship in both builds — and the whole point of the
    // `direct` flavor is that it carries no restricted permissions and no Play
    // Services dependency (SPEC.md §3.4).
    //
    // The implementations go in the flavor source sets this creates:
    // `src/play/kotlin/…/geofence/` and `src/direct/kotlin/…/foreground/`, which
    // is the split SPEC.md §11's tree draws. Flavor-scoped dependencies
    // (`playImplementation`, `directImplementation`) and per-flavor manifest
    // entries — the foreground-service declaration in particular — hang off the
    // same seam.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("direct") { dimension = "distribution" }
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
}
