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
