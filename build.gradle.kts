import com.diffplug.spotless.LineEnding
import groovy.json.JsonSlurper
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.diffplug.spotless") version "8.2.1"
}

val hostToolsOutputDir = layout.buildDirectory.dir("outputs/host-tools")
val stagedScrcpyServer = hostToolsOutputDir.map { it.file("scrcpy/scrcpy-server") }
val scrcpyServerReleaseApkDir = layout.projectDirectory.dir("scrcpy/server/build/outputs/apk/release")
val embeddedAdbOutputDir = layout.buildDirectory.dir("prebuilt-adb")
val nightlyAdbRepo = providers.gradleProperty("androidToolsNightlyRepo").orElse("XiaoTong6666/android-tools")
val nightlyAdbTag = providers.gradleProperty("androidToolsNightlyTag").orElse("nightly")

data class NightlyAdbAsset(
    val abi: String,
    val assetName: String,
)

val nightlyAdbAssets =
    listOf(
        NightlyAdbAsset(abi = "arm64-v8a", assetName = "adb-arm64-v8a"),
        NightlyAdbAsset(abi = "armeabi-v7a", assetName = "adb-armeabi-v7a"),
    )

fun readHttpBody(connection: HttpURLConnection): String = (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }

fun openHttpConnection(
    url: String,
    token: String?,
    accept: String,
): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
    instanceFollowRedirects = true
    connectTimeout = 30_000
    readTimeout = 120_000
    requestMethod = "GET"
    setRequestProperty("Accept", accept)
    setRequestProperty("User-Agent", "scrcpyandroid2-gradle")
    if (!token.isNullOrBlank()) {
        setRequestProperty("Authorization", "Bearer $token")
    }
}

fun httpGetText(
    url: String,
    token: String?,
    accept: String = "application/vnd.github+json",
): String {
    val connection = openHttpConnection(url, token, accept)
    try {
        val body = readHttpBody(connection)
        if (connection.responseCode !in 200..299) {
            error("HTTP ${connection.responseCode} when fetching $url\n$body")
        }
        return body
    } finally {
        connection.disconnect()
    }
}

fun downloadToFile(
    url: String,
    destination: File,
    token: String?,
    accept: String = "application/octet-stream",
) {
    destination.parentFile.mkdirs()
    val connection = openHttpConnection(url, token, accept)
    try {
        if (connection.responseCode !in 200..299) {
            val body = readHttpBody(connection)
            error("HTTP ${connection.responseCode} when downloading $url\n$body")
        }
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } finally {
        connection.disconnect()
    }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) {
                break
            }
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

val downloadNightlyAdb by tasks.registering {
    group = "build"
    description = "Downloads the nightly Android adb binaries from the android-tools GitHub release and verifies SHA-256."

    val outputFiles =
        nightlyAdbAssets.map { nightlyAsset ->
            embeddedAdbOutputDir.get().file("${nightlyAsset.abi}/adb").asFile
        }
    val checksumOutput = embeddedAdbOutputDir.get().file("SHA256SUMS").asFile
    val metadataOutput = embeddedAdbOutputDir.get().file("nightly.json").asFile

    outputs.files(outputFiles + listOf(checksumOutput, metadataOutput))
    outputs.upToDateWhen { false }

    doLast {
        val repo = nightlyAdbRepo.get()
        val tag = nightlyAdbTag.get()
        val githubToken =
            providers.environmentVariable("ANDROID_TOOLS_GITHUB_TOKEN").orNull
                ?: providers.environmentVariable("GITHUB_TOKEN").orNull
        val releaseApiUrl = "https://api.github.com/repos/$repo/releases/tags/$tag"
        val tempDir = layout.buildDirectory.dir("downloads/android-tools-nightly").get().asFile

        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val releaseJsonText = httpGetText(releaseApiUrl, githubToken)
        val releaseJson = JsonSlurper().parseText(releaseJsonText) as Map<*, *>
        val releaseAssets = (releaseJson["assets"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        val assetsByName =
            releaseAssets.associateBy { asset ->
                asset["name"]?.toString() ?: error("Encountered a nightly release asset without a name.")
            }

        val checksumAsset =
            assetsByName["SHA256SUMS"]
                ?: error("Nightly release $repo@$tag does not contain a SHA256SUMS asset.")
        val metadataAsset =
            assetsByName["nightly.json"]
                ?: error("Nightly release $repo@$tag does not contain a nightly.json asset.")

        val checksumTemp = File(tempDir, "SHA256SUMS")
        val metadataTemp = File(tempDir, "nightly.json")
        downloadToFile(checksumAsset["browser_download_url"].toString(), checksumTemp, githubToken, "*/*")
        downloadToFile(metadataAsset["browser_download_url"].toString(), metadataTemp, githubToken, "*/*")

        val expectedChecksums =
            checksumTemp
                .readLines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        null
                    } else {
                        val parts = trimmed.split(Regex("\\s+"), limit = 2)
                        if (parts.size != 2) {
                            error("Malformed SHA256SUMS line: $line")
                        }
                        parts[1].removePrefix("*") to parts[0].lowercase()
                    }
                }.toMap()

        nightlyAdbAssets.forEach { nightlyAsset ->
            val releaseAsset =
                assetsByName[nightlyAsset.assetName]
                    ?: error("Nightly release $repo@$tag is missing ${nightlyAsset.assetName}.")
            val expectedSha =
                expectedChecksums[nightlyAsset.assetName]
                    ?: error("SHA256SUMS does not contain ${nightlyAsset.assetName}.")
            val downloadedAsset = File(tempDir, nightlyAsset.assetName)
            val outputFile = embeddedAdbOutputDir.get().file("${nightlyAsset.abi}/adb").asFile

            downloadToFile(releaseAsset["browser_download_url"].toString(), downloadedAsset, githubToken, "*/*")

            val actualSha = sha256Hex(downloadedAsset)
            if (actualSha != expectedSha) {
                error(
                    "SHA-256 mismatch for ${nightlyAsset.assetName}: expected $expectedSha, got $actualSha",
                )
            }

            outputFile.parentFile.mkdirs()
            downloadedAsset.copyTo(outputFile, overwrite = true)
            outputFile.setExecutable(true)
        }

        checksumOutput.parentFile.mkdirs()
        checksumTemp.copyTo(checksumOutput, overwrite = true)
        metadataTemp.copyTo(metadataOutput, overwrite = true)

        println(
            "Downloaded android-tools nightly release ${releaseJson["name"] ?: tag} " +
                "(published ${releaseJson["published_at"] ?: "unknown"})",
        )
    }
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
