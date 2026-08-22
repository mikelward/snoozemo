plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        // minSdk 34 (Android 14) buys requestAddTileService, POST_NOTIFICATIONS,
        // the modern Wi-Fi APIs, and the PendingIntent overload of
        // startActivityAndCollapse — the tile's whole arm path — without version
        // branches (SPEC.md §11).
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
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
        }
        release {
            // R8 and the signing configs land with the release plumbing in
            // Phase 6; until then a local release build stays unminified and
            // unsigned so fresh clones build cleanly.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
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
