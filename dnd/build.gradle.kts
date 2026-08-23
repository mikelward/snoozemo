plugins {
    alias(libs.plugins.android.library)
}

// All NotificationManager / AutomaticZenRule contact lives here (SPEC.md §11),
// so the rest of the app never touches the DND APIs directly.

android {
    namespace = "app.snoozemo.dnd"
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
    implementation(project(":core"))
}
