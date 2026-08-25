plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // Exports the dependency graph as JSON for the Licenses page. Applied only
    // in :app; declared here so the version is shared like every other plugin.
    alias(libs.plugins.aboutlibraries) apply false
    // Crash reporting. Applied by :app only when its (untracked)
    // google-services.json is present, so a fresh clone or a fork builds with
    // Crashlytics dormant; declared here so the versions resolve either way.
    // See docs/crashlytics.md.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
