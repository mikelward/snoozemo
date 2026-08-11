plugins {
    alias(libs.plugins.kotlin.jvm)
    // For the `api` configuration below.
    `java-library`
}

// Deliberately a plain Kotlin JVM module, not an Android library (SPEC.md §11):
// the state machine takes a clock and two injected interfaces and nothing else,
// so keeping the Android SDK off its compile classpath is what stops decision
// logic drifting into a Service or a composable where only a device can test it.
// `./gradlew :core:test` therefore needs no Android SDK and no Robolectric.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // `api`, not `implementation`: PresenceMonitor returns a Flow, so coroutines
    // are part of this module's public surface.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The seam above is a convention until something enforces it, and `:core:test`
// passing is not that something: CI's runner has an Android SDK, so converting
// this module to an Android library would leave the tests green and the boundary
// quietly gone. This task is the enforcement — it fails when an Android plugin or
// an Android artifact enters `:core`, whether or not the tests still pass.
val verifyNoAndroid = tasks.register("verifyNoAndroid") {
    description = "Fails if an Android plugin or dependency enters :core (SPEC.md §11)."
    group = "verification"

    // Read at configuration time; asserted at execution time, so the task stays
    // configuration-cache friendly.
    val androidPlugins = plugins
        .map { it.javaClass.name }
        .filter { it.startsWith("com.android.") }
    // Both classpaths, not just runtime: a `compileOnly` dependency on the
    // Android APIs is absent at runtime, so checking only `runtimeClasspath`
    // would wave through domain code importing `Context` — the exact breach this
    // task exists to prevent.
    //
    // findByName, not named(): an Android module has neither of these (it has
    // per-variant configurations instead), and a task that failed to *configure*
    // would report a missing configuration rather than the rule that was broken.
    // Falling back to empty lets the plugin check below produce the message that
    // actually tells the reader what happened.
    val classpaths = listOf("compileClasspath", "runtimeClasspath")
        .mapNotNull { name -> configurations.findByName(name)?.let { name to it } }
    val classpathArtifacts = provider {
        classpaths.associate { (name, configuration) ->
            name to configuration.incoming.artifactView { lenient(true) }.artifacts.artifacts
                .map { it.id.componentIdentifier.displayName }
        }
    }

    doLast {
        check(androidPlugins.isEmpty()) {
            "SPEC.md §11 keeps :core free of Android so the state machine stays testable " +
                "on the JVM, but these Android plugins are applied: $androidPlugins. " +
                "Move the Android-dependent code to :app, :dnd, :presence, or :tile, or " +
                "change the decision in SPEC.md first."
        }
        // `com.google.android` matters as much as the obvious two: Play Services
        // — including the geofencing client the `play` flavor is built on — ships
        // under com.google.android.gms, and it is the single most likely thing to
        // drift into the core.
        val bannedPrefixes = listOf("com.android", "androidx.", "com.google.android")
        val offenders = classpathArtifacts.get()
            .mapValues { (_, ids) -> ids.filter { id -> bannedPrefixes.any(id::startsWith) } }
            .filterValues { it.isNotEmpty() }
        check(offenders.isEmpty()) {
            "SPEC.md §11 keeps :core free of Android so the state machine stays testable " +
                "on the JVM, but these Android artifacts are on its classpath: $offenders."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoAndroid)
}
