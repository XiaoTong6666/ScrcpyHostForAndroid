plugins {
    id("com.android.library")
}

android {
    namespace = "org.libsdl.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets.named("main") {
        java.directories.add(rootProject.file("SDL/android-project/app/src/main/java").absolutePath)
    }
}
