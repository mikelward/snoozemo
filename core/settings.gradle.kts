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
        // Same repository the outer build declares, for the same coordinate.
        // Deliberately fine for this offramp: what it exists to avoid is
        // `google()` and AGP, not the network — see below.
        maven {
            name = "androidlog"
            url = uri("https://raw.githubusercontent.com/mikelward/androidlog/maven")
            content { includeGroup("com.mikelward.androidlog") }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// mikelward/androidlog. `logging-core` is now published, so the ordinary path
// here resolves it from the repository declared above like any other
// dependency — the coordinate being unpublished is what used to force a
// composite build into this offramp at all (Codex, PR #148).
//
// The opt-in mirrors the outer build's, and keeps its one hard-won detail:
// it includes the library's `logging-core/` directory, NOT the repository root.
// `includeBuild` configures every project in the included build rather than
// only the one substitution selects, so including the root here would evaluate
// `:logging-android` -- which applies AGP -- and the root build script, which
// resolves the AGP plugin marker even under `apply false`. Both come from
// `google()`, so this offramp would fail on exactly the dependency it exists to
// avoid (Codex, PR #148; confirmed with an `error(...)` probe in that module,
// which fired from this build). `logging-core/` carries its own settings file
// for this, and is Android-free by construction -- `:logging-core:verifyNoAndroid`
// is what enforces that.
//
// Paths are relative to THIS file's directory (`core/`), so they are one level
// deeper than the outer build's: `../.androidlog` is a checkout at the repo
// root, `../../androidlog` the sibling clone.
val androidlogLocal = providers.gradleProperty("androidlogLocal").orNull?.let { raw ->
    when (raw.trim().lowercase()) {
        "", "true" -> true
        "false" -> false
        else -> error("androidlogLocal must be true or false (or bare), not \"$raw\"")
    }
} ?: false

if (androidlogLocal) {
    val androidlog = listOf(file("../.androidlog"), file("../../androidlog"))
        .firstOrNull { it.isDirectory }
        ?: error(
            "androidlogLocal is set but no checkout was found: " +
                "git clone https://github.com/mikelward/androidlog ../../androidlog, " +
                "or drop the property to resolve the published version"
        )
    includeBuild(androidlog.resolve("logging-core"))
    logger.lifecycle("androidlog: using the local checkout at $androidlog")
}

rootProject.name = "core"
