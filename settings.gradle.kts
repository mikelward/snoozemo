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
        // mikelward/androidlog publishes its own Maven repository into the
        // `maven` branch of that repository, which raw.githubusercontent.com
        // serves. A Maven repository is a static directory tree over HTTP, so
        // there is no third party in the trust path.
        //
        // Scoped to that one group: without `includeGroup` this repository
        // would be consulted for every unresolved coordinate in the build.
        maven {
            name = "androidlog"
            url = uri("https://raw.githubusercontent.com/mikelward/androidlog/maven")
            content { includeGroup("com.mikelward.androidlog") }
        }
    }
}

rootProject.name = "Snoozemo"

// mikelward/androidlog is resolved from the repository declared above, by
// version, like any other dependency — see gradle/libs.versions.toml.
//
// It used to be a composite build tracking @main, which meant nothing to bump
// and nothing to forget to bump. What it also meant was that androidlog's
// toolchain sat alongside this one in a single Gradle invocation, and AGP's
// `AgpVersionCompatibilityRule` refuses to compare two versions at all — so a
// patch bump there failed configuration in every consumer at once. A resolved
// AAR carries no `AgpVersionAttr`; the version is the thing to bump, and the
// weekly Gradle batch bumps it.
//
// OPTING IN to a local checkout instead: `-PandroidlogLocal`, or
// `androidlogLocal=true` in a local gradle.properties. Off by default, and
// keyed on the property rather than on the directory merely existing — a
// sibling clone left over from working on androidlog itself must not silently
// reinstate that lockstep. Read as a BOOLEAN, because `isPresent` is true for
// any value at all and `androidlogLocal=false` would then mean "on".
val androidlogLocal = providers.gradleProperty("androidlogLocal").orNull?.let { raw ->
    when (raw.trim().lowercase()) {
        // Gradle hands `-PandroidlogLocal` with no value over as an empty string.
        "", "true" -> true
        "false" -> false
        else -> error("androidlogLocal must be true or false (or bare), not \"$raw\"")
    }
} ?: false

if (androidlogLocal) {
    val androidlog = listOf(file(".androidlog"), file("../androidlog"))
        .firstOrNull { it.isDirectory }
        ?: error(
            "androidlogLocal is set but no checkout was found: " +
                "git clone https://github.com/mikelward/androidlog ../androidlog, " +
                "or drop the property to resolve the published version"
        )
    includeBuild(androidlog)
    logger.lifecycle("androidlog: using the local checkout at $androidlog")
}

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
