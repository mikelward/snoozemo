plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // Exports the dependency graph as JSON for the Licenses page. Applied only
    // in :app; declared here so the version is shared like every other plugin.
    alias(libs.plugins.aboutlibraries) apply false
}
