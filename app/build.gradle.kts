plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The fallback keeps a checkout with no git (a source zip, a fork's odd CI)
// building, but it must never be silent: falling back means versionCode 1, and a
// build that quietly claims version 1 is either rejected by Play or looks like a
// downgrade to a tester's device. So every path that misses the real value says
// so at warn level, naming what failed.
//
// Phase 6 should harden this further: once there is a release job, a release
// build whose version can't be derived should fail outright rather than warn
// (TODO.md).
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
val isShallowClone: Boolean =
    gitOutput("rev-parse", "--is-shallow-repository", fallback = "unknown") == "true"

val gitCommitCount: Int = gitOutput("rev-list", "--count", "HEAD", fallback = "1").let { count ->
    val parsed = count.toIntOrNull()
    when {
        parsed == null -> {
            logger.warn("Version derivation: commit count \"$count\" is not a number; using 1.")
            1
        }
        isShallowClone -> {
            logger.warn(
                "Version derivation: shallow clone — the commit count ($parsed) is truncated, so " +
                    "this build's versionCode is NOT monotonic and must not be published. " +
                    "Run `git fetch --unshallow` for a publishable build.",
            )
            parsed
        }
        else -> parsed
    }
}
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")
val baseVersionName = "0.1"

android {
    namespace = "app.snoozemo"
    // The platform the remote-session provisioning hook seeds
    // (.claude/hooks/session-start.sh); AGP installs the compileSdk minor
    // platform itself on the first build.
    compileSdk = 36

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
}
