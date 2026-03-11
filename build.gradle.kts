import com.diffplug.spotless.LineEnding

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.diffplug.spotless") version "8.2.1"
}

val hostAdbBinaryName =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
val androidToolsBuildDir = layout.buildDirectory.dir("android-tools-host-nopatch")
val termuxAdbOutputDir = layout.buildDirectory.dir("outputs/termux-adb")
val hostToolsOutputDir = layout.buildDirectory.dir("outputs/host-tools")
val stagedTermuxAdbDir = hostToolsOutputDir.map { it.dir("termux-adb") }
val stagedHostAdb = hostToolsOutputDir.map { it.file("adb/$hostAdbBinaryName") }
val stagedScrcpyServer = hostToolsOutputDir.map { it.file("scrcpy/scrcpy-server") }
val scrcpyServerReleaseApkDir = layout.projectDirectory.dir("scrcpy/server/build/outputs/apk/release")

val configureHostAdb by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the standalone android-tools host build for adb."

    inputs.dir(layout.projectDirectory.dir("android-tools"))
    outputs.file(androidToolsBuildDir.map { it.file("CMakeCache.txt") })

    commandLine(
        "cmake",
        "-S",
        "android-tools",
        "-B",
        androidToolsBuildDir.get().asFile.absolutePath,
        "-DANDROID_TOOLS_PATCH_VENDOR=OFF",
        "-DANDROID_TOOLS_USE_BUNDLED_FMT=ON",
    )
}

val buildHostAdb by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the upstream host adb binary through android-tools."
    dependsOn(configureHostAdb)

    inputs.dir(layout.projectDirectory.dir("android-tools"))
    outputs.file(androidToolsBuildDir.map { it.file("vendor/$hostAdbBinaryName") })

    commandLine(
        "cmake",
        "--build",
        androidToolsBuildDir.get().asFile.absolutePath,
        "--target",
        "adb",
        "-j4",
    )
}

val stageHostAdb by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies the built host adb binary into a stable Gradle output location."
    dependsOn(buildHostAdb)

    from(androidToolsBuildDir.map { it.file("vendor/$hostAdbBinaryName") })
    into(hostToolsOutputDir.map { it.dir("adb") })
}

val stageScrcpyServerBinary by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies scrcpy-server into the shared host-tools output directory."
    dependsOn(":scrcpyServer:assembleRelease")

    from(scrcpyServerReleaseApkDir) {
        include("*-release-unsigned.apk")
        rename { "scrcpy-server" }
    }
    into(hostToolsOutputDir.map { it.dir("scrcpy") })
}

tasks.register("assembleHostAdb") {
    group = "build"
    description = "Builds and stages the standalone host adb binary."
    dependsOn(stageHostAdb)
}

tasks.register("assembleHostRuntimeDebug") {
    group = "build"
    description = "Builds and stages host adb together with scrcpy-server."
    dependsOn(stageHostAdb, stageScrcpyServerBinary)
}

data class TermuxAbi(
    val abi: String,
)

val termuxAbis =
    listOf(
        TermuxAbi(
            abi = "arm64-v8a",
        ),
        TermuxAbi(
            abi = "armeabi-v7a",
        ),
    )

termuxAbis.forEach { termuxAbi ->
    val taskSuffix =
        termuxAbi.abi
            .split('-', '_')
            .joinToString(separator = "") { part -> part.replaceFirstChar(Char::uppercaseChar) }

    tasks.register<Exec>("buildTermuxAdb$taskSuffix") {
        group = "build"
        description =
            "Builds the Termux-patched Android adb binary for ${termuxAbi.abi} using the local NDK."

        val buildScript = layout.projectDirectory.file("scripts/build_termux_adb.sh")
        inputs.file(buildScript)
        inputs.dir(layout.projectDirectory.dir("android-tools"))
        outputs.file(termuxAdbOutputDir.map { it.file("${termuxAbi.abi}/adb") })
        workingDir = layout.projectDirectory.asFile

        doFirst {
            commandLine(buildScript.asFile.absolutePath, termuxAbi.abi)
        }
    }
}

tasks.register("assembleTermuxAdb") {
    group = "build"
    description = "Builds the Termux-patched Android adb binary for all configured Android ABIs."
    dependsOn(
        termuxAbis.map { termuxAbi ->
            val taskSuffix =
                termuxAbi.abi
                    .split('-', '_')
                    .joinToString(separator = "") { part -> part.replaceFirstChar(Char::uppercaseChar) }
            "buildTermuxAdb$taskSuffix"
        },
    )
}

val stageTermuxAdb by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages Termux-patched Android adb binaries into a stable Gradle output location."
    dependsOn("assembleTermuxAdb")

    from(termuxAdbOutputDir)
    into(stagedTermuxAdbDir)
}

tasks.register("assembleBackendRuntime") {
    group = "build"
    description = "Builds and stages host bridge runtime plus embedded Termux adb binaries."
    dependsOn(stageHostAdb, stageScrcpyServerBinary, stageTermuxAdb)
}

tasks.register<Exec>("launchRemoteScrcpyServer") {
    group = "application"
    description =
        "Uses the staged host adb to connect to a network device, push scrcpy-server, and launch it. Pass -Ptarget=HOST[:PORT]."
    dependsOn("assembleHostRuntimeDebug")

    val launcherScript = layout.projectDirectory.file("scripts/run_remote_scrcpy_server.sh")
    inputs.file(launcherScript)
    workingDir = layout.projectDirectory.asFile

    doFirst {
        val target =
            project.findProperty("target")?.toString()?.takeIf { it.isNotBlank() }
                ?: error("Missing -Ptarget=HOST[:PORT] for launchRemoteScrcpyServer")

        val args = mutableListOf(launcherScript.asFile.absolutePath, target)
        project.findProperty("localPort")?.toString()?.takeIf { it.isNotBlank() }?.let {
            args += listOf("--local-port", it)
        }

        commandLine(args)
    }
}

tasks.register<Exec>("connectRemoteAdb") {
    group = "application"
    description =
        "Uses the staged host adb to run adb connect HOST[:PORT]. Pass -Ptarget=HOST[:PORT]."
    dependsOn("assembleHostAdb")

    val connectScript = layout.projectDirectory.file("scripts/connect_remote_adb.sh")
    inputs.file(connectScript)
    workingDir = layout.projectDirectory.asFile

    doFirst {
        val target =
            project.findProperty("target")?.toString()?.takeIf { it.isNotBlank() }
                ?: error("Missing -Ptarget=HOST[:PORT] for connectRemoteAdb")

        commandLine(connectScript.asFile.absolutePath, target)
    }
}

tasks.register<Exec>("runAdbBridge") {
    group = "application"
    description =
        "Starts a local HTTP bridge so the Android app can trigger adb connect. Optional: -PbridgeHost=0.0.0.0 -PbridgePort=8765"
    dependsOn("assembleHostRuntimeDebug")

    val bridgeScript = layout.projectDirectory.file("scripts/adb_bridge_server.py")
    inputs.file(bridgeScript)
    workingDir = layout.projectDirectory.asFile

    doFirst {
        val bridgeHost = project.findProperty("bridgeHost")?.toString()?.ifBlank { "0.0.0.0" } ?: "0.0.0.0"
        val bridgePort = project.findProperty("bridgePort")?.toString()?.ifBlank { "8765" } ?: "8765"
        commandLine(
            "python3",
            bridgeScript.asFile.absolutePath,
            "--host",
            bridgeHost,
            "--port",
            bridgePort,
        )
        environment("ADB_BRIDGE_ADB_BIN", stagedHostAdb.get().asFile.absolutePath)
        environment("ADB_BRIDGE_SCRCPY_SERVER_BIN", stagedScrcpyServer.get().asFile.absolutePath)
    }
}

spotless {
    lineEndings = LineEnding.UNIX

    java {
        target(
            "app/src/*/java/**/*.java",
            "sdl-android-java/src/*/java/**/*.java",
        )
        targetExclude("**/api/**", "**/build/**")

        palantirJavaFormat()
        importOrder()
        removeUnusedImports()
        formatAnnotations()
    }

    kotlin {
        target(
            "app/src/*/java/**/*.kt",
            "sdl-android-java/src/*/java/**/*.kt",
        )
        targetExclude("**/api/**", "**/build/**")

        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "max_line_length" to "off",
                "ktlint_standard_comment-wrapping" to "disabled",
            ),
        )
    }

    kotlinGradle {
        target(
            "build.gradle.kts",
            "settings.gradle.kts",
            "app/build.gradle.kts",
            "sdl-android-java/build.gradle.kts",
        )

        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "max_line_length" to "off",
            ),
        )
    }

    format("cpp") {
        target(
            "app/src/main/jni/**/*.c",
            "app/src/main/jni/**/*.cpp",
            "app/src/main/jni/**/*.h",
            "app/src/main/jni/**/*.hpp",
        )
        targetExclude("**/api/**", "**/build/**")

        clangFormat("21.0.0").style("file")
    }
}

tasks.register("format") {
    dependsOn("spotlessApply")
    group = "formatting"
    description = "Formats the code using Spotless."
}
