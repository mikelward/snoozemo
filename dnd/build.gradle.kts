plugins {
    alias(libs.plugins.android.library)
}

// All NotificationManager / AutomaticZenRule contact lives here (SPEC.md §11),
// so the rest of the app never touches the DND APIs directly and the SDK-34
// fallbacks stay in one place.

android {
    namespace = "app.snoozemo.dnd"
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
}
