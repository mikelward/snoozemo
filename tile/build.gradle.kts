plugins {
    alias(libs.plugins.android.library)
}

// `SnoozeTileService` — thin, delegating to :core (SPEC.md §11). Empty until
// Phase 2, which adds the tile, the trampoline activity, and the ongoing
// notification. The module exists now so the seam is there from the start
// rather than being carved out of :app later.

android {
    namespace = "app.snoozemo.tile"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
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
    implementation(project(":core"))
    implementation(project(":dnd"))
}
