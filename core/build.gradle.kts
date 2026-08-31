import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

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
    // The shared debug log. `api` for the same reason: `SnoozeDebugLog` extends
    // `DebugLog`, and `safe(...)` / `sensitive(...)` are called from `:app`.
    //
    // A published coordinate now, pinned in gradle/libs.versions.toml and moved
    // by the weekly Gradle batch. `-PandroidlogLocal` substitutes a local
    // checkout when both repositories are being changed at once.
    api(libs.androidlog.logging.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Self-test only. `-PverifyNoAndroidSelfTest` puts a real Android artifact
    // on both classpaths so CI can prove `verifyNoAndroid` **fails**, which a
    // passing run cannot: the bug it replaced was a false pass, and that
    // version passed with exactly this dependency present.
    //
    // Deliberately not in the version catalog. A catalog entry would read as a
    // dependency this project has, and it is the opposite — a thing that must
    // never resolve here. It does not need to: `:core` is a JVM module and this
    // is an AAR, and being unresolvable is the case the guard now sees and the
    // old one could not.
    //
    // An accidental activation fails the build loudly rather than passing it,
    // which is the safe direction for a hook like this to be wrong in.
    if (providers.gradleProperty("verifyNoAndroidSelfTest").isPresent) {
        implementation("androidx.core:core-ktx:1.13.1")
    }
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
    // The **requested dependencies**, not the resolved artifacts. The artifact
    // view this used to read is the obvious way to write the check and it is
    // wrong in the direction that passes: an androidx dependency is an AAR, a
    // JVM module cannot resolve an AAR, and the `lenient(true)` such a view
    // needs — without it an unrelated failure fails this task with the wrong
    // message — then swallows exactly the failure being checked for. The list
    // came back empty and the guard passed, so `:core` could have taken an
    // androidx dependency and CI would have stayed green.
    //
    // `resolutionResult.allComponents` has the same hole for a different
    // reason: an unresolvable dependency is never a component. `./gradlew
    // :core:dependencies` reports such a dependency as FAILED, and FAILED is
    // absent from that list.
    //
    // `allDependencies` carries resolved and unresolved alike, and the
    // requested selector names the module either way. Reading it resolves the
    // graph but downloads and unpacks nothing.
    //
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
    //
    // Requested **and** actual, because dependency substitution rewrites one
    // into the other: `DependencyResult.requested` keeps the original selector,
    // so a rule substituting an innocuous module for an Android one would
    // recreate the false pass this task exists to close (Codex, PR #137). The
    // actual target is `selected` where the dependency resolved and `attempted`
    // where it did not — and an Android artifact reaching :core is unlikely to
    // resolve, so the unresolved branch is the one that matters most here.
    val classpathModules = provider {
        classpaths.associate { (name, configuration) ->
            name to configuration.incoming.resolutionResult.allDependencies.flatMap { result ->
                val requested = (result.requested as? ModuleComponentSelector)
                    ?.let { "${it.group}:${it.module}" }
                val actual = when (result) {
                    is ResolvedDependencyResult ->
                        result.selected.moduleVersion?.let { "${it.group}:${it.name}" }
                    is UnresolvedDependencyResult ->
                        (result.attempted as? ModuleComponentSelector)
                            ?.let { "${it.group}:${it.module}" }
                    else -> null
                }
                listOfNotNull(requested, actual)
            }.distinct()
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
        //
        // `org.robolectric` joins them because `org.robolectric:android-all`
        // puts the whole framework on a classpath under a group none of the
        // other three match. A denylist is incomplete by construction — an
        // Android SDK jar can ship under any coordinate at all — so if this
        // list ever has to grow again, the honest fix is inspecting the
        // resolved jars for `android.*` packages instead.
        val bannedPrefixes =
            listOf("com.android", "androidx.", "com.google.android", "org.robolectric")
        val modules = classpathModules.get()
        // A resolution that found nothing at all is the false pass this guard
        // is most likely to fail by, so it is checked rather than assumed:
        // every configuration here has at least the Kotlin stdlib on it.
        check(modules.values.any { it.isNotEmpty() }) {
            "verifyNoAndroid resolved no modules on ${modules.keys}, so it proved nothing. " +
                "The dependency graph could not be read — fix that rather than trusting this task."
        }
        val offenders = modules
            .mapValues { (_, ids) -> ids.filter { id -> bannedPrefixes.any(id::startsWith) } }
            .filterValues { it.isNotEmpty() }
        check(offenders.isEmpty()) {
            "SPEC.md §11 keeps :core free of Android so the state machine stays testable " +
                "on the JVM, but these Android modules are on its classpath: $offenders."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoAndroid)
}
