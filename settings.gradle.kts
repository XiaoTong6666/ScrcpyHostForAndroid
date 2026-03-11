import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.1.0"
        id("com.android.library") version "9.1.0"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScrcpyHostForAndroid"
include(":app")
include(":sdlAndroidJava")

val scrcpyServerPatchFile = file("patches/scrcpy/server/0001-gradle-agp-compat.patch")
val scrcpyServerBuildFile = file("scrcpy/server/build.gradle")
val scrcpyServerBackupFile = file("build/gradle-patches/scrcpy/server.build.gradle.bak")
var scrcpyServerPatched = false

fun runCommand(workingDir: File, vararg command: String): Pair<Int, String> {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val process =
        ProcessBuilder(*command)
            .directory(workingDir)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
    process.inputStream.copyTo(stdout)
    process.errorStream.copyTo(stderr)
    val exitCode = process.waitFor()
    val output = buildString {
        append(stdout.toString())
        append(stderr.toString())
    }.trim()
    return exitCode to output
}

fun restoreScrcpyServerBuildGradleIfNeeded() {
    if (scrcpyServerBackupFile.exists()) {
        scrcpyServerBuildFile.parentFile.mkdirs()
        scrcpyServerBuildFile.writeText(scrcpyServerBackupFile.readText())
        scrcpyServerBackupFile.delete()
    }
}

fun applyScrcpyServerPatchIfNeeded() {
    if (!scrcpyServerPatchFile.exists()) {
        return
    }

    restoreScrcpyServerBuildGradleIfNeeded()

    val repoDir = file("scrcpy")
    val (alreadyAppliedExit, _) =
        runCommand(
            repoDir,
            "git",
            "apply",
            "-R",
            "--check",
            "-p1",
            scrcpyServerPatchFile.absolutePath,
        )
    if (alreadyAppliedExit == 0) {
        return
    }

    val (canApplyExit, canApplyOutput) =
        runCommand(
            repoDir,
            "git",
            "apply",
            "--check",
            "-p1",
            scrcpyServerPatchFile.absolutePath,
        )
    if (canApplyExit != 0) {
        throw GradleException(
            "Failed to apply ${scrcpyServerPatchFile.absolutePath} before configuring :scrcpyServer\n$canApplyOutput",
        )
    }

    scrcpyServerBackupFile.parentFile.mkdirs()
    scrcpyServerBackupFile.writeText(scrcpyServerBuildFile.readText())

    val (applyExit, applyOutput) =
        runCommand(
            repoDir,
            "git",
            "apply",
            "-p1",
            scrcpyServerPatchFile.absolutePath,
        )
    if (applyExit != 0) {
        restoreScrcpyServerBuildGradleIfNeeded()
        throw GradleException(
            "Failed to apply ${scrcpyServerPatchFile.absolutePath} before configuring :scrcpyServer\n$applyOutput",
        )
    }

    scrcpyServerPatched = true
}

applyScrcpyServerPatchIfNeeded()

gradle.buildFinished {
    if (scrcpyServerPatched) {
        restoreScrcpyServerBuildGradleIfNeeded()
    }
}

include(":scrcpyServer")

project(":scrcpyServer").projectDir = file("scrcpy/server")
project(":sdlAndroidJava").projectDir = file("sdl-android-java")
