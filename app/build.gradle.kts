plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// GitHub Actions sets CI=true. It gates R8 (see buildTypes below): every build
// CI produces — the Play AAB and both flavors' debug APKs — is minified, so the
// shipping artifact and the artifact PR CI checks go through the same pipeline,
// while a local build skips R8 and stays fast to iterate on. Mirrors the
// sibling Simmo and Type Launcher repos.
val isCiBuild: Boolean = System.getenv("CI") == "true"

// Crash reporting (SPEC.md §12): Crashlytics activates per build. Both plugins
// are applied only when the untracked google-services.json is present, so a
// fresh clone, a fork, and CI all build with Crashlytics dormant and nothing
// to configure — the app's own gate then reports "unavailable" and Settings
// offers no switch over a reporter that isn't there. See docs/crashlytics.md.
val firebaseConfigFile = file("google-services.json")
val firebaseConfigured: Boolean = firebaseConfigFile.exists()
if (firebaseConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)

    // ...but only for `play`. `direct` is the sideload/F-Droid build and
    // carries no Play Services dependency at all (SPEC.md §3.4) — that is the
    // flavor's reason to exist, and an F-Droid build cannot contain a
    // proprietary reporter. Both plugins are project-wide once applied, so the
    // flavor split is enforced here by disabling the `direct` variants' tasks.
    //
    // Disabling stops them regenerating, but Gradle does not delete a disabled
    // task's earlier output: a checkout that ever built these variants with
    // the plugins enabled still holds the project's google_app_id under
    // build/generated/res/, and the resource merge would happily package it
    // into the `direct` APK — Firebase would then initialize in the one build
    // that must never reach the network. So purge that directory ahead of the
    // merge rather than only skipping the regeneration. (Two paths: AGP names
    // the directory after the task; older versions used google-services/.)
    val purgeDirectFirebaseResources = tasks.register<Delete>("purgeDirectGoogleServicesResources") {
        description = "Deletes Firebase resources generated for the direct flavor, which ships without them."
        delete(
            layout.buildDirectory.dir("generated/res/processDirectDebugGoogleServices"),
            layout.buildDirectory.dir("generated/res/processDirectReleaseGoogleServices"),
            layout.buildDirectory.dir("generated/res/google-services/directDebug"),
            layout.buildDirectory.dir("generated/res/google-services/directRelease"),
        )
    }
    afterEvaluate {
        tasks.matching {
            // Every google-services / Crashlytics task either plugin registers
            // for a `direct*` variant, matched by name rather than listed: the
            // Crashlytics plugin's task set depends on whether R8 runs, so an
            // explicit list would have silently stopped covering a variant the
            // day minification was turned on (it since has, below) — and it already
            // registers more than the mapping-file upload the obvious list
            // would have named (injectCrashlyticsVersionControlInfo, which
            // writes version-control metadata into the artifact).
            (it.name.startsWith("process") && it.name.endsWith("GoogleServices")) ||
                it.name.startsWith("injectCrashlytics") ||
                it.name.startsWith("uploadCrashlytics")
        }.configureEach {
            if (name.contains("Direct")) {
                enabled = false
            }
        }
        tasks.matching { it.name.startsWith("mergeDirect") && it.name.endsWith("Resources") }
            .configureEach { dependsOn(purgeDirectFirebaseResources) }
    }
}

// The fallback keeps a checkout with no git (a source zip, a fork's odd CI)
// building, but it must never be silent: falling back means versionCode 1, and a
// build that quietly claims version 1 is either rejected by Play or looks like a
// downgrade to a tester's device. So every path that misses the real value says
// so at warn level, naming what failed — and a **release** build refuses
// outright (`checkReleaseVersionDerivation` below): a warning in a CI log is
// not where anyone would find it, and a published fallback is either a
// rejected upload or a phantom downgrade (TODO.md, Phase 6).
fun gitOutput(vararg args: String, fallback: String): String {
    val command = "git ${args.joinToString(" ")}"
    return try {
        val execOutput = providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }
        val exitValue = execOutput.result.get().exitValue
        val output = execOutput.standardOutput.asText.get().trim()
        when {
            exitValue != 0 -> {
                logger.warn("Version derivation: `$command` exited $exitValue; using \"$fallback\".")
                fallback
            }
            output.isEmpty() -> {
                logger.warn("Version derivation: `$command` produced no output; using \"$fallback\".")
                fallback
            }
            else -> output
        }
        // Broad on purpose: this is version derivation at configuration time, and
        // no failure of it should stop the build — but none may pass unnoticed
        // either, so every one is named in the warning.
    } catch (e: Exception) {
        logger.warn("Version derivation: `$command` failed (${e.javaClass.simpleName}: ${e.message}); using \"$fallback\".")
        fallback
    }
}

// Monotonic versionCode as long as main only moves forward; Play rejects an AAB
// whose versionCode is <= the highest already uploaded. CI checks out with
// fetch-depth: 0 for exactly this reason.
//
// A shallow clone is the nastiest case and the one the error handling above
// cannot catch: `rev-list --count` *succeeds* and returns a plausible smaller
// number, so the build silently produces a non-monotonic versionCode — a
// rejected upload, or a newer tester build that looks older than one already
// installed. AGENTS.md warns about the same trap when reporting versionCodes by
// hand. So detect it explicitly rather than trusting the count.
// Why this build's version is not publishable, or empty when it is. Debug
// builds warn and carry on; the release guard below turns any entry here into
// a refusal, because each one means the versionCode is a fallback or a
// truncation rather than the count Play's monotonicity depends on.
val versionProblems = mutableListOf<String>()

// Anything but a literal "false" is a problem: "unknown" means the depth
// question went unanswered (git missing, or too old for the flag), and treating
// an unanswered question as "not shallow" is exactly the bypass the release
// guard exists to close — the count would pass as complete without anything
// having established that it is.
val shallowAnswer: String = gitOutput("rev-parse", "--is-shallow-repository", fallback = "unknown")
val isShallowClone: Boolean = shallowAnswer == "true"
if (shallowAnswer != "true" && shallowAnswer != "false") {
    versionProblems += "whether the clone is shallow could not be determined (git answered " +
        "\"$shallowAnswer\"), so the commit count cannot be trusted as complete"
}

val gitCommitCount: Int = gitOutput("rev-list", "--count", "HEAD", fallback = "").let { count ->
    val parsed = count.toIntOrNull()
    when {
        parsed == null -> {
            if (count.isNotEmpty()) {
                logger.warn("Version derivation: commit count \"$count\" is not a number; using 1.")
            }
            versionProblems += "the commit count could not be read from git, so versionCode fell back to 1"
            1
        }
        isShallowClone -> {
            logger.warn(
                "Version derivation: shallow clone — the commit count ($parsed) is truncated, so " +
                    "this build's versionCode is NOT monotonic and must not be published. " +
                    "Run `git fetch --unshallow` for a publishable build.",
            )
            versionProblems += "the clone is shallow, so the commit count ($parsed) is truncated " +
                "and the versionCode is not monotonic (run `git fetch --unshallow`)"
            parsed
        }
        else -> parsed
    }
}
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown").also {
    if (it == "unknown") {
        versionProblems += "the commit hash could not be read, so the versionName names no commit"
    }
}
val baseVersionName = "0.1"

// The Phase 6 guard: a release variant refuses to build on an underived
// version instead of warning. Wired ahead of every `*Release` variant's build
// via its `pre<Variant>Build` anchor and nowhere else, so debug builds, tests,
// and lint on a shallow CI checkout keep working exactly as before — the
// fallback exists for them. Failing at execution time rather than
// configuration time is what keeps `./gradlew test` runnable from the same
// broken checkout the guard exists to catch.
val checkReleaseVersionDerivation = tasks.register("checkReleaseVersionDerivation") {
    description = "Fails a release build whose version was not derived from real git history."
    val problems = versionProblems.toList()
    doLast {
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Refusing to build a release with an underived version: " +
                    problems.joinToString("; ") +
                    ". A fallback versionCode is either a rejected Play upload or a phantom " +
                    "downgrade on a tester's device, and a warning is not where anyone would find it.",
            )
        }
    }
}
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("ReleaseBuild")) {
        dependsOn(checkReleaseVersionDerivation)
    }
}

android {
    namespace = "app.snoozemo"
    // The platform the remote-session provisioning hook seeds
    // (.claude/hooks/session-start.sh); AGP installs the compileSdk minor
    // platform itself on the first build.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.snoozemo"
        // minSdk 35 (Android 15) buys requestAddTileService, POST_NOTIFICATIONS,
        // the modern Wi-Fi APIs, and the PendingIntent overload of
        // startActivityAndCollapse — the tile's whole arm path — without version
        // branches, and (raised from 34, PR #88) Modes UI, which is what makes
        // `Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS` resolve for the
        // Filters row (SPEC.md §11).
        minSdk = 35
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
    }

    signingConfigs {
        // CI materializes the Play upload keystore from a secret and points
        // RELEASE_KEYSTORE_FILE at it for the internal-track upload (Phase 6,
        // `docs/play-store-internal-track.md`). Play App Signing re-signs the
        // AAB with its own managed key before delivery, so this upload key
        // only authenticates to Play — losing it costs an upload-key reset,
        // never the listing. Local builds without the env var produce an
        // unsigned release build, so a fresh clone still builds cleanly with
        // no secrets (mirrors the sibling Simmo repo's release signingConfig).
        create("release") {
            val keystorePath = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    // The distribution split of SPEC.md §3.4, and the only place the two
    // channels' constraints diverge. `play` is the shipping build for any Play
    // track and carries ACCESS_BACKGROUND_LOCATION and the Geofencing API;
    // `direct` is the sideload/F-Droid build with a foreground service, no
    // restricted permissions, and no Play Services dependency. The flavors
    // select which PresenceMonitor is bound; everything above that interface is
    // shared (Phase 3 and Phase 7 add the implementations).
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            isDefault = true
        }
        create("direct") {
            dimension = "distribution"
            versionNameSuffix = "-direct"
        }
    }

    buildTypes {
        debug {
            // Co-installs beside a release build instead of colliding on the
            // package name.
            applicationIdSuffix = ".debug"
            // `app.snoozemo.debug` is unknown to Play — an update check would
            // always come back empty, so skip it (and the Play IPC) entirely.
            // Only meaningful on the `play` flavor; the `direct` flavor's own
            // update checker never reads this field (SPEC.md's flavor split).
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "false")
            // No R8 here, deliberately. AGP disables optimization and
            // obfuscation for any debuggable build ("All code optimizations
            // and obfuscation are disabled for debuggable builds") and warns
            // when you ask anyway, so minifying this variant could only ever
            // have run the shrinker — a strict subset of what the release
            // variants already run on every pull request (the `Build release
            // APKs` step in .github/workflows/ci.yml). It bought no coverage
            // and cost a slower build, so the debug APK stays unminified and
            // fast to install. A shrunk artifact to test on a device comes
            // from `CI=true ./gradlew assembleRelease`.
        }
        release {
            // Only the Play build can be updated by Play, so only it asks.
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "true")
            // Full R8 — shrinking, optimization and obfuscation — CI-only (see
            // isCiBuild). Play requires coverage across all three from February
            // 2027, so this is a distribution requirement rather than a size
            // choice (SPEC.md §3.7). The PR build job runs it too
            // (`assembleRelease`), so the deploy job is never the first build
            // to find out that something was stripped or renamed.
            isMinifyEnabled = isCiBuild
            isShrinkResources = isCiBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only attach the signingConfig when CI (or a developer) has
            // actually populated it — an unset storeFile would fail
            // bundleRelease for anyone without the release secrets.
            if (!providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // Only for PLAY_UPDATE_CHECKS_ENABLED below — the `play` flavor's own
        // update-check gate, mirroring the sibling Simmo repo.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Roborazzi reads these as system properties, not Gradle properties, so the
// `-P` flags CI passes have to be forwarded into the test JVM. Without a flag
// the screenshot tests still run — they just render and assert without touching
// a PNG, which is what keeps `./gradlew test` from rewriting snapshots on every
// developer's machine.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ----------------------------------------------------------------------------
// Open-source attribution -> committed res/raw/aboutlibraries.json, per flavor
// ----------------------------------------------------------------------------
// AboutLibraries' Android auto-integration needs the legacy AppExtension that
// AGP 9 removed, so the plugin can't generate res/raw for us at build time.
// Instead we commit the export as a resource and regenerate it on demand with
// `./gradlew :app:exportBundledLicenses`; CI reruns it and fails on drift. The
// Licenses page reads the committed R.raw.aboutlibraries at runtime.
//
// One export per flavor, not one shared file: `play` bundles Play's in-app
// update library (and the Play Services stack under it) and `direct` bundles
// none of it (SPEC.md §3.4). A shared list would have the sideload build
// claiming to ship Play code it doesn't contain, which is the opposite of what
// an attribution page is for. So the plugin collects the union of both release
// variants into a build-directory scratch file, and the task below writes one
// filtered resource per flavor from it.
aboutLibraries {
    collect {
        // Both release variants: their runtime classpaths differ, and the
        // scratch export has to be a superset of each. Scoping to release also
        // keeps test/debug-only artifacts (JUnit, Robolectric, Roborazzi,
        // Compose tooling) out; includePlatform = false drops BOM/platform
        // POMs that ship no runtime artifact.
        filterVariants.addAll("playRelease", "directRelease")
        includePlatform = false
    }
    export {
        outputFile = layout.buildDirectory.file("aboutlibraries/all-variants.json").get().asFile
        prettyPrint = true
        // Drop the full SPDX license text: it's resolved from a network-fetched
        // SPDX list whose exact wording varies by environment, so committing it
        // would make the regenerate-and-diff CI check non-deterministic. The
        // page still shows each license's name, SPDX id, and URL.
        excludeFields.add("License.content")
    }
}

// The plugin walks the dependency *graph*, so the scratch export lists nodes
// that resolve to no bundled artifact even before the flavor split: KMP
// metadata coordinates (e.g. androidx.compose.ui:ui, which selects
// ...:ui-android) and the org.jetbrains.compose redirect modules that alias to
// the androidx artifacts on Android. Both would render as duplicate rows.
// Intersecting against a flavor's own release runtime classpath drops them and
// settles the flavor question in the same pass -- what's left is exactly what
// that flavor's APK bundles.
@Suppress("UNCHECKED_CAST")
tasks.register("exportBundledLicenses") {
    description = "Exports open-source attributions, one per flavor, filtered to that APK's bundled artifacts."
    group = "build"
    dependsOn("exportLibraryDefinitions")
    val scratchFile = layout.buildDirectory.file("aboutlibraries/all-variants.json")
    val runtimeClasspaths = mapOf(
        "play" to configurations.named("playReleaseRuntimeClasspath"),
        "direct" to configurations.named("directReleaseRuntimeClasspath"),
    )
    val outputFiles = runtimeClasspaths.keys.associateWith { file("src/$it/res/raw/aboutlibraries.json") }
    doLast {
        runtimeClasspaths.forEach { (flavor, classpath) ->
            val bundled = classpath.get().incoming
                .artifactView { lenient(true) }.artifacts.artifacts
                .mapNotNull { it.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .map { "${it.moduleIdentifier.group}:${it.moduleIdentifier.name}" }
                .toSet()
            val root = groovy.json.JsonSlurper().parse(scratchFile.get().asFile) as MutableMap<String, Any?>
            val libraries = root["libraries"] as List<Map<String, Any?>>
            val kept = libraries.filter { (it["uniqueId"] as String) in bundled }
            root["libraries"] = kept
            // Prune any license no longer referenced by a kept library.
            val used = kept.flatMap { (it["licenses"] as? List<String>).orEmpty() }.toSet()
            (root["licenses"] as? MutableMap<String, Any?>)?.keys?.retainAll(used)
            val out = outputFiles.getValue(flavor)
            out.parentFile.mkdirs()
            out.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(root)) + "\n")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":dnd"))
    implementation(project(":presence"))
    implementation(project(":tile"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // Not used directly — pins the transitive `androidx.fragment` version the
    // `registerForActivityResult` API family needs (1.3.0+; the `play` flavor's
    // Play Services stack alone pulls a pre-1.3 `fragment` that fails Android
    // Lint's `InvalidFragmentVersionForActivityResult` check across every
    // `registerForActivityResult` call in the app, not only the new one this
    // PR adds).
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Dispatchers.Main for the service's presence collection — stated
    // explicitly rather than leaned on transitively through Compose.
    implementation(libs.kotlinx.coroutines.android)
    // The §6.10 periodic backstop: a deferrable, batched wake per half hour
    // while armed — the cheap kind of periodic (SPEC.md §9).
    implementation(libs.androidx.work.runtime)
    // The `play` flavor's update banner (Play's in-app update flow) — scoped
    // to this flavor alone, same as `presence`'s geofencing dependency, since
    // `direct` carries no Play Services dependency at all (SPEC.md §3.4).
    "playImplementation"(libs.play.app.update)
    // Crash reporting, `play` only — `direct` carries no Play Services
    // dependency (SPEC.md §3.4), and its own CrashReporter is a no-op. Present
    // in every `play` build so the wiring compiles, but inert unless the build
    // had a google-services.json: with no FirebaseApp initialized the gate
    // reports unavailable and nothing is ever collected.
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.crashlytics)
    // Reads the committed res/raw/aboutlibraries.json for the Licenses page.
    // Only `rememberLibraries` and the `Libs`/`Library` model are used -- the
    // artifact's own list UI is not.
    implementation(libs.aboutlibraries.compose.m3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    // Debug-only, and safe only because unit tests exist for the debug build
    // type alone here — `./gradlew :app:tasks` lists `testDirectDebugUnitTest`
    // and `testPlayDebugUnitTest` and nothing else. This dependency is what
    // declares the activity `createAndroidComposeRule` launches, so setting
    // `testBuildType` to anything else, or turning release unit tests back on,
    // needs this moved to `testImplementation` first or the screenshot tests
    // fail on a variant that has no activity to launch.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
