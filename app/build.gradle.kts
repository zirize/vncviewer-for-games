import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// ── Development connection defaults ─────────────────────────────────────────
// 🔑 Editing the source every time you point at a different server mixes those edits into commits
//    and, worse, means writing a password into the repository.
//    ⇒ They are read from local.properties, which is untracked.
//      vnc.dev.host=192.0.2.10        example only - RFC 5737 documentation space, not a real host
//      vnc.dev.port=5900
//      vnc.dev.password=...
//    Left empty, VncConnectionConfig's own defaults apply.
// 🔑 In the Gradle Kotlin DSL, `java` is shadowed by an extension name and java.util.* cannot be
//    resolved - hence the import above.
// ── Release signing ─────────────────────────────────────────────────────────
// 🔑 The upload key (`.jks`) and its password live **outside the repository** - not in this file,
//    not anywhere in the tree. If `keystore.properties` exists (untracked, 0600) the release is
//    signed with that key; otherwise it falls back to the debug key, which is the everyday build
//    you install straight onto a device. The presence of one file decides it.
val signProps = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val devProps = Properties().apply {
  rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun devProp(key: String) = (devProps.getProperty(key) ?: "").trim()

android {
    namespace = "io.github.zirize.vncviewerforgames"
    compileSdk = 36
    defaultConfig {
        // 🔴 **This is the permanent store identifier. Once registered it cannot be changed.**
        //
        // 🔑 **The original identifier is only used when the upload key is wired in.**
        //    keystore.properties present = the original author's build ⇒ the app that gets published.
        //    Absent = somebody else's build (a fork, a contributor, CI) ⇒ **a different identifier**.
        //
        // 🔴 **Why split it** - without this, someone who forks, builds and publishes without
        //    thinking ends up with **the same app** as the original on the store, and the
        //    identifier is permanent.
        //    ⇒ The default has to be the safe one. Documentation saying "change this" goes unread.
        //
        // 🔑 If you forked this, change the `else` value to your own (`io.github.<account>.<name>`).
        applicationId =
            if (signProps.getProperty("storeFile") != null) "tech.doldam.remotepad"
            else "io.github.zirize.vncviewerforgames"

        // 🔑 The visible app name follows the same rule - see res/values/strings.xml.
        resValue("string", "app_name",
            if (signProps.getProperty("storeFile") != null) "RemotePad" else "VNC for Games")
        minSdk = 24
        targetSdk = 36
        // 🔴 **Raise this on every upload** - the store rejects a repeated versionCode.
        //    🔑 If the console says the code is already in use, **raise it again and rebuild**.
        //       Skipped numbers cost nothing; the only irreversible move is going *down*.
        //    ℹ️ versionName is what users see, so it changes only when the app does.
        versionCode = 3
        versionName = "1.1"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++14"
            }
        }
        // 🔑 **Instrumentation only - the shipping default is untouched** (0 keeps the existing
        //    availableProcessors() capped at 4). The point is not to pin the worker count: setting
        //    it to 1 makes DecodeManager skip the queue and dispatcher entirely (the
        //    threads.size()==1 fast path), which is the only way to isolate "is the dispatcher
        //    eating the gains from parallelism" as a single variable.
        //    🚫 Do not ship a number tuned on one phone; it differs per device.
        //    Usage: ./gradlew :app:assembleRelease -PvncDecoderThreads=1
        // 🔑 **The switch that makes a build for one particular person.** To build another layout:
        //      ./gradlew :app:assembleRelease -PvncProfile=lefty.json
        //    If profiles/lefty.json exists, that is what gets baked in.
        buildConfigField("String", "VNC_PROFILE",
            "\"${project.findProperty("vncProfile") as String? ?: "default.json"}\"")
        buildConfigField("int", "VNC_DECODER_THREADS",
            "${(project.findProperty("vncDecoderThreads") as String? ?: "0").toInt()}")
        // 🔑 **Instrumentation only.** −1 leaves it alone (shipping default qualityLevel = 8).
        //    It separates "the load is pinned at 84MB/s because of transport" from "because of
        //    decoding": if lowering quality reduces bytes and Mpx/s **rises**, transport was the limit.
        //    🚫 Do not pin a quality level for a release. A human eye judges quality.
        //    Usage: ./gradlew :app:assembleRelease -PvncQualityLevel=5
        // 🔑 Instrumentation only. −1 leaves it alone (shipping default compressLevel = 2).
        //    Usage: ./gradlew :app:assembleRelease -PvncCompressLevel=9
        // 🔑 Instrumentation only. **−99 means leave it alone** (use the configured value).
        //    −2 = automatic (follows the load), −1 = defer to the server, 0 = 4:4:4, 1 = 4:2:0,
        //    2 = 4:2:2, 3 = greyscale.
        //    🔴 "Leave it alone" must not be −2: −2 now means automatic, so the two would collide.
        //    Usage: ./gradlew :app:assembleRelease -PvncSubsampling=-2
        buildConfigField("int", "VNC_SUBSAMPLING",
            "${(project.findProperty("vncSubsampling") as String? ?: "-99").toInt()}")
        buildConfigField("int", "VNC_COMPRESS_LEVEL",
            "${(project.findProperty("vncCompressLevel") as String? ?: "-1").toInt()}")
        buildConfigField("int", "VNC_QUALITY_LEVEL",
            "${(project.findProperty("vncQualityLevel") as String? ?: "-1").toInt()}")
        buildConfigField("String", "VNC_DEV_HOST", "\"${devProp("vnc.dev.host")}\"")
        buildConfigField("String", "VNC_DEV_PORT", "\"${devProp("vnc.dev.port")}\"")
        buildConfigField("String", "VNC_DEV_PASSWORD", "\"${devProp("vnc.dev.password")}\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 🔑 **The button layout lives in `profiles/`, not in code.** The whole folder is baked in as
    //    assets, so changing a layout means editing JSON and rebuilding.
    //    🔑 No copy task: one line does it, and a copy is a thing that can go stale.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("profiles"))

    signingConfigs {
        if (signProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 🔑 With keystore.properties present it signs with the upload key, otherwise with
            //    the debug key.
            //    Release builds are measurably faster than debug (fps 20.8 → 33.2), so this is the
            //       everyday build too - and without a signature it cannot even be installed,
            //       which is why the fallback exists.
            //    🚫 A debug-signed artefact **cannot be published**. Check which key signed it
            //       before uploading (scripts/verify-signing.sh).
            signingConfig = signingConfigs.findByName("upload")
                ?: signingConfigs.getByName("debug")
            // 🔑 R8 on: Play Console asks for it, and this is the everyday build.
            //    Rules and the reasoning live in app/proguard-rules.pro - the short version is
            //    that only two things here depend on a name surviving: the JNI entry point and
            //    the enum constants SettingsCodec persists.
            // 🔴 **Unit tests cannot vouch for a minified build** - they run on the JVM over
            //    unminified classes, so `build.sh test` stays green no matter what R8 breaks.
            //    ⇒ After touching R8 config or proguard-rules.pro, install the release APK and
            //      connect for real. Anything less is the green light that means nothing.
            isMinifyEnabled = true
            // 🔑 AGP 9 applies optimized resource shrinking on its own once this is on; the old
            //    `android.r8.optimizedResourceShrinking` flag was an AGP 8.12-8.13 stopgap and is
            //    **not** needed here.
            // ℹ Assets are never shrunk, so profiles/ (wired in above as an asset dir) is untouched.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // 🔑 app_name is generated here (see defaultConfig) so it can follow the same
        //    rule as applicationId. That needs this feature on.
        resValues = true
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

// 🔴 **One thing is always blocked at build time: a profile with no settings exit.**
//
// 🔑 **Why not leave it to the unit tests** - `assembleRelease` does not run them, so only
//    someone who remembers to validate is safe. That is the same as documentation nobody reads.
//    Added on 2026-09-17 after confirming that an APK really could be built from a profile with
//    no settings exit.
// 🔑 **It checks that one thing only.** It is the only mistake a user cannot recover from;
//    everything else is checked far more thoroughly by `./gradlew :app:previewProfiles`.
//    A heavy gate is a gate people learn to disable.
val selectedProfileName = project.findProperty("vncProfile") as String? ?: "default.json"
val selectedProfileFile = rootProject.file("profiles/$selectedProfileName")

val checkSelectedProfile = tasks.register("checkSelectedProfile") {
    group = "verification"
    description = "Checks the selected profile has a settings exit (full validation: previewProfiles)"
    val f = selectedProfileFile
    val name = selectedProfileName
    inputs.file(f).withPropertyName("selectedProfile")
    outputs.upToDateWhen { true }
    doLast {
        if (!f.isFile) {
            throw GradleException(
                "no such profile `$name`: ${f.path}\n" +
                "  give a name from profiles/, for example -PvncProfile=default.json")
        }
        val json = groovy.json.JsonSlurper().parse(f) as Map<*, *>
        val buttons = json["buttons"] as? List<*>
            ?: throw GradleException("profile `$name` has no `buttons`")
        val hasExit = buttons.any { b ->
            val action = (b as? Map<*, *>)?.get("action") as? Map<*, *>
            action?.get("type") == "ui" && action["command"] == "settings"
        }
        if (!hasExit) {
            throw GradleException(
                "profile `$name` has no button that opens settings.\n" +
                "  🔴 Built like this, the user cannot change the server address, the layout, or\n" +
                "     anything else from inside the app. There is no way back in.\n" +
                "  => Keep one button with {\"type\":\"ui\",\"command\":\"settings\"}.\n" +
                "  For the rest of the checks: ./gradlew :app:previewProfiles")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkSelectedProfile) }

// 🔴 **Gradle does not know that `profiles/` is an input to the tests.**
//    Without this, adding or editing a profile leaves testDebugUnitTest UP-TO-DATE, so validation
//    **does not run** while still reporting green, and no preview SVG appears.
//    🔑 That is the "the instrument is broken and it looks like success" failure this repository
//       worries about most - and it was here. Added on 2026-09-17 after hitting it.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("profiles"))
        .withPropertyName("overlayProfiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// 🔑 **The alias an agent uses.** One line to answer "did I get it right" after changing a layout:
//      ./gradlew :app:previewProfiles
//    It runs the validation (the unit tests) and renders one SVG per profile into
//    app/build/preview/.
//    🔑 A test renders the pictures because splitting this into two commands means forgetting one.
tasks.register("previewProfiles") {
    group = "verification"
    description = "Validates the profiles and renders SVG previews into app/build/preview/"
    dependsOn("testDebugUnitTest")
    // 🔑 The configuration cache is on, so `doLast` must not touch `layout` or `project`.
    //    The path is captured at configuration time and passed through as a value.
    val previewDir = layout.buildDirectory.dir("preview").get().asFile
    doLast {
        val dir = previewDir
        println("\n▸ previews: $dir")
        dir.walkTopDown().filter { it.isFile }.sorted().forEach { println("    ${it.relativeTo(dir)}") }
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  // 🔴 Android's `org.json` is a stub on the unit-test JVM: every method throws.
  //    A real implementation is what lets the profile parser be tested without a device, which is
  //    the core promise of this repository.
  testImplementation("org.json:json:20240303")
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
}
