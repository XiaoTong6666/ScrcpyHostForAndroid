plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val embeddedAdbAbis = listOf("arm64-v8a", "armeabi-v7a")
val embeddedAdbAssetsDir = layout.buildDirectory.dir("generated/assets/embedded-adb")
val embeddedScrcpyAssetsDir = layout.buildDirectory.dir("generated/assets/embedded-scrcpy")
val termuxAdbOutputDir = rootProject.layout.buildDirectory.dir("prebuilt-adb").get()
val stagedScrcpyServerOutputFile = rootProject.layout.buildDirectory.file("outputs/host-tools/scrcpy/scrcpy-server")
val scrcpyServerVersion: String =
    Regex("versionName\\s+\"([^\"]+)\"")
        .find(rootProject.layout.projectDirectory.file("scrcpy/server/build.gradle").asFile.readText())
        ?.groupValues
        ?.get(1)
        ?: error("Unable to parse scrcpy server version from scrcpy/server/build.gradle")

val verifyEmbeddedAdbAssets by tasks.registering {
    group = "build"
    description = "Verifies that prebuilt Android adb binaries exist under build/prebuilt-adb."
    dependsOn(rootProject.tasks.named("downloadNightlyAdb"))
    mustRunAfter(rootProject.tasks.named("downloadNightlyAdb"))

    val expectedAdbBinaries = embeddedAdbAbis.map { abi -> termuxAdbOutputDir.file("$abi/adb").asFile }
    inputs.files(expectedAdbBinaries)

    doLast {
        val missingBinaries = expectedAdbBinaries.filterNot { it.isFile }
        if (missingBinaries.isEmpty()) {
            return@doLast
        }

        val missingAbis = missingBinaries.map { it.parentFile.name }
        throw GradleException(
            buildString {
                appendLine("Missing prebuilt Android adb binaries under ${termuxAdbOutputDir.asFile}.")
                appendLine("Fetch the latest verified nightly build first:")
                appendLine("./gradlew downloadNightlyAdb")
            },
        )
    }
}

val prepareEmbeddedAdbAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies prebuilt Android adb binaries into app assets per ABI."
    dependsOn(verifyEmbeddedAdbAssets)
    mustRunAfter(rootProject.tasks.named("downloadNightlyAdb"))

    from(termuxAdbOutputDir) {
        include("**/adb")
        eachFile {
            path = "termux-adb/$path"
        }
        includeEmptyDirs = false
    }
    into(embeddedAdbAssetsDir)
}

val prepareEmbeddedScrcpyAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies scrcpy-server binary into app assets."
    dependsOn(rootProject.tasks.named("stageScrcpyServerBinary"))

    from(stagedScrcpyServerOutputFile) {
        eachFile {
            path = "scrcpy/$name"
        }
        includeEmptyDirs = false
    }
    into(embeddedScrcpyAssetsDir)
}

android {
    namespace = "io.github.xiaotong6666.scrcpy"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.xiaotong6666.scrcpy"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SCRCPY_SERVER_VERSION", "\"$scrcpyServerVersion\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
    signingConfigs {
        var keystoreFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
        if (keystoreFile.exists()) {
            register("debugKey") {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("debugKey")
        }
        release {
            signingConfig = signingConfigs.findByName("debugKey")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    sourceSets.named("main") {
        assets.directories.add(embeddedAdbAssetsDir.get().asFile.absolutePath)
        assets.directories.add(embeddedScrcpyAssetsDir.get().asFile.absolutePath)
    }
}

tasks.named("preBuild") {
    dependsOn(prepareEmbeddedAdbAssets, prepareEmbeddedScrcpyAssets)
}

dependencies {
    implementation(project(":sdlAndroidJava"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.miuix.android)
}
