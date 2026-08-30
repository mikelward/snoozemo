pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Snoozemo"

// mikelward/androidlog, tracked @main. No version, no tag, no SHA: it is a
// composite build, so a merge there is in this app's next build with nothing to
// bump — and nothing to forget to bump, which is what four hand-maintained
// copies of this code had already proved is the failure mode.
//
// CI checks it out into `.androidlog/`; locally, clone it as a sibling. The
// error message says so rather than failing with a Gradle stack trace about a
// directory nobody has heard of.
val androidlog = listOf(file(".androidlog"), file("../androidlog")).firstOrNull { it.isDirectory }
    ?: error("androidlog not found — git clone https://github.com/mikelward/androidlog ../androidlog")
includeBuild(androidlog)

// The module tree of SPEC.md §11. `:core` is a plain Kotlin JVM module, not an
// Android library: that is the seam the maintainer asked for up front, and the
// build is what enforces it — `SnoozeController` cannot reach for a `Context`,
// a `Location`, or a composable's state, because they are not on its classpath.
// It also means `./gradlew :core:test` runs the state machine's tests without
// an Android SDK at all.
include(":app")
include(":core")
include(":dnd")
include(":presence")
include(":tile")
