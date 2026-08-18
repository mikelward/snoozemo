// Sandbox-friendly settings root for the pure-Kotlin `:core` module.
//
// The outer Snoozemo build declares AGP's android.application / android.library
// plugin markers (apply false) at its root, and Gradle resolves every
// `plugins { }` block across the whole project graph during configuration —
// even for a task that only touches `:core`, which has no Android dependency
// of its own. In an environment where Google Maven is unreachable (e.g. an
// agent sandbox behind an egress allowlist), the outer build can't even
// configure, so `:core:test` — which needs no Android SDK by design
// (SPEC.md §11) — is blocked by a repository the module never actually uses.
//
// This inner settings file exposes `:core` rooted right where it already
// lives (no include(), so the directory containing this file — `core/` — is
// the single project), pulling plugins from only the Gradle Plugin Portal and
// Maven Central. Invoke it from this directory:
//
//   cd core && ../gradlew test
//
// CI keeps using the outer build; this is purely an offramp for sandboxes and
// for anyone iterating on the state machine without configuring AGP.
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "core"
