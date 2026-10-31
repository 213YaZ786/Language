// Copied from Calculator's app/build.gradle.kts; the speech engine's build (whisperVersion, fetchWhisper, cmake,
// c++_shared, legacy packaging) from SMS's app/build.gradle.kts and Keyboard's (same pins), whisper.cpp being
// https://github.com/ggml-org/whisper.cpp
import java.net.URI
import java.security.MessageDigest

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// whisper.cpp, the engine that writes speech out: see fetchWhisper below (SMS's app/build.gradle.kts, same pins).
val whisperVersion = "1.9.4"
val whisperSha = "57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae"
val whisperRoot = layout.buildDirectory.dir("whisper").get().asFile
val whisperSrc = File(whisperRoot, "whisper.cpp-$whisperVersion")

android {
    namespace = "com.yaz.voice"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yaz.voice"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        // 64-bit ARM phones, and the x86_64 emulators (as Keyboard's app/build.gradle.kts lists its ABIs).
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        // c++_shared as SMS and Keyboard: one runtime for the app's native libraries
        // https://developer.android.com/ndk/guides/cpp-support#static_runtimes ; WHISPER_SRC is where fetchWhisper
        // unpacks the source.
        externalNativeBuild {
            cmake {
                arguments("-DWHISPER_SRC=${whisperSrc.absolutePath}", "-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("voice.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("voice.storePassword").orNull
                keyAlias = providers.gradleProperty("voice.keyAlias").orNull
                keyPassword = providers.gradleProperty("voice.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Only attached when the signing properties are supplied, so a
            // local build without a keystore still produces an unsigned APK.
            if (providers.gradleProperty("voice.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES"
        )
        // whisper.cpp's libraries compressed in the APK, unpacked once at install (the engine loads its CPU variants
        // from the library folder by path).
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// whisperVersion and its source's checksum are pinned at the top.
// The engine that writes speech out: whisper.cpp (MIT, github.com/ggml-org/whisper.cpp), its source fetched at the
// pinned release and checked against its SHA-256, then compiled here; the speech model itself is fetched by the app,
// only when the user asks for it. Copied from SMS's app/build.gradle.kts (fetchWhisper).
val fetchWhisper by tasks.registering {
    val root = whisperRoot
    val src = whisperSrc
    val version = whisperVersion
    val sha = whisperSha
    inputs.property("version", version)
    outputs.dir(src)
    doLast {
        if (File(src, "CMakeLists.txt").exists()) return@doLast
        val bytes = URI("https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$version.tar.gz").toURL().openStream().use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(got == sha) { "whisper.cpp: checksum does not match" }
        root.mkdirs()
        val archive = File(root, "whisper.tar.gz").apply { writeBytes(bytes) }
        val tar = ProcessBuilder("tar", "xzf", archive.path, "-C", root.path).inheritIO().start()
        check(tar.waitFor() == 0) { "whisper.cpp: could not unpack" }
        archive.delete()
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("generateJsonModel")) dependsOn(fetchWhisper)
}
