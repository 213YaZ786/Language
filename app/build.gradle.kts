// Copied from Calculator's app/build.gradle.kts; the speech engine's build (whisperVersion, fetchWhisper, cmake,
// c++_shared, legacy packaging) from SMS's app/build.gradle.kts and Keyboard's (same pins), whisper.cpp being
// https://github.com/ggml-org/whisper.cpp
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Screens drawn on the JVM and compared, as SMS's app/build.gradle.kts (roborazzi plugin).
    alias(libs.plugins.roborazzi)
}

// whisper.cpp, the engine that writes speech out: see fetchWhisper below (SMS's app/build.gradle.kts, same pins).
val whisperVersion = "1.9.4"
val whisperSha = "57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae"
val whisperRoot = layout.buildDirectory.dir("whisper").get().asFile
val whisperSrc = File(whisperRoot, "whisper.cpp-$whisperVersion")

android {
    namespace = "com.yaz.language"
    compileSdk = 37
    // The engine's NDK (engine.yml), so the libc++_shared.so whisper's CMake packs is the engine's
    // (offline-translator's app/build.gradle.kts line 16 sets ndkVersion the same way).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.yaz.language"
        minSdk = 31
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
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
            val storeFilePath = providers.gradleProperty("language.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("language.storePassword").orNull
                keyAlias = providers.gradleProperty("language.keyAlias").orNull
                keyPassword = providers.gradleProperty("language.keyPassword").orNull
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
            if (providers.gradleProperty("language.storeFile").isPresent) {
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
        // The translation service's AIDL files (src/main/aidl): AGP 8 and later turn AIDL off by default
        // https://developer.android.com/build/releases/past-releases/agp-8-0-0-release-notes
        aidl = true
    }

    // Screens drawn on the JVM by Robolectric and compared by Roborazzi (its README's setup), copied from SMS's
    // app/build.gradle.kts (testOptions).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                it.maxHeapSize = "4096m"
                // Robolectric on JDK 17 and later (https://robolectric.org/getting-started/).
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
                )
            }
        }
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
    // uniffi's Kotlin bindings call the engine through JNA; the aar carries libjnidispatch.so for Android
    // (offline-translator's app/build.gradle.kts line 449, there 5.14.0; 5.19.1 is the newest on Maven Central,
    // 2026-06-12). Literal here: gradle/libs.versions.toml is the base kit's synced copy.
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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

// The translation engine (offline-translator's Rust crate, trimmed, GPL-3.0, github.com/DavidVentura/offline-translator
// at v0.8.7, commit e7716c6859e3d90b8883c667c1a3cfeef407df08): its libbindings per ABI and its uniffi Kotlin bindings,
// built by this repository's .github/workflows/engine.yml (release engine-0.8.7-p1, branch engine), fetched here at the
// pinned SHA-256 and checked before anything is written, so the repository holds no binary. Copied from SMS's
// app/build.gradle.kts (fetchChatmail: pinned release, checksum before writing, skip when the target already matches,
// jniLibs srcDir, preBuild dependsOn). Rust is not built here (offline-translator's buildBindings does).
val engineRelease = "https://github.com/213YaZ786/Language/releases/download/engine-0.8.7-p1"
// uniffi loads "bindings" (findLibraryName in uniffi/bindings/bindings.kt), so every ABI's file is libbindings.so.
val engineLibraries = mapOf(
    "arm64-v8a" to ("libbindings-arm64-v8a.so" to "e5e691b561cf23dedb553e9ef5c0758050fb19f721a85cbe0f5bbecc4d73ebac"),
    "x86_64" to ("libbindings-x86_64.so" to "5ca25eda48d82178c3184304c4560a3158228072eb0b792396f4a4c04423d78f")
)
val engineKotlin = "bindings-kotlin.tar.gz" to "75e976407efba0e9d094be52419783df77a45a7327a39a0a1e16d69e5f193ef5"
val engineJniDir = layout.buildDirectory.dir("engine/jniLibs")
val engineArchive = layout.buildDirectory.file("engine/bindings-kotlin.tar.gz")
// Same folder as offline-translator's app/build.gradle.kts line 22 and 380 (generatedBindingsDir).
val engineKotlinDir = layout.buildDirectory.dir("generated/source/uniffi/kotlin")

// Injected services, not Project.copy, at execution time: Gradle's docs, "Working with files" (configuration cache)
// https://docs.gradle.org/current/userguide/working_with_files.html
interface EngineServices {
    @get:javax.inject.Inject val fs: FileSystemOperations
    @get:javax.inject.Inject val archives: ArchiveOperations
}

val fetchEngine by tasks.registering {
    val jniDir = engineJniDir
    val archiveFile = engineArchive
    val kotlinDir = engineKotlinDir
    val libraries = engineLibraries
    val kotlinTar = engineKotlin
    val release = engineRelease
    val services = objects.newInstance<EngineServices>()
    inputs.property("release", release)
    outputs.dir(jniDir)
    outputs.dir(kotlinDir)
    doLast {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        libraries.forEach { (abi, asset) ->
            val (name, sha) = asset
            val target = jniDir.get().dir(abi).file("libbindings.so").asFile
            if (target.exists() && sha256(target.readBytes()) == sha) return@forEach
            target.parentFile.mkdirs()
            val bytes = URI("$release/$name").toURL().openStream().use { it.readBytes() }
            check(sha256(bytes) == sha) { "engine $abi: checksum does not match" }
            target.writeBytes(bytes)
        }

        val (tarName, tarSha) = kotlinTar
        val archive = archiveFile.get().asFile
        if (!archive.exists() || sha256(archive.readBytes()) != tarSha) {
            archive.parentFile.mkdirs()
            val bytes = URI("$release/$tarName").toURL().openStream().use { it.readBytes() }
            check(sha256(bytes) == tarSha) { "engine bindings: checksum does not match" }
            archive.writeBytes(bytes)
        }
        // Every entry listed before anything is extracted: Apache Commons Compress's TarArchiveInputStream
        // (https://commons.apache.org/proper/commons-compress/examples.html#Tar), already on Gradle's classpath
        // through the Android plugin. Refused: an absolute path, a ".." part, an entry outside uniffi/, a link,
        // a file that is not a .kt.
        GZIPInputStream(archive.inputStream().buffered()).use { gz ->
            val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz)
            while (true) {
                val entry = tar.nextEntry ?: break
                val path = entry.name
                check(!path.startsWith("/") && !path.contains("\\") && !path.contains('\u0000')) { "engine bindings: absolute path $path" }
                val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
                check(parts.none { it == ".." }) { "engine bindings: '..' in $path" }
                check(!entry.isSymbolicLink && !entry.isLink) { "engine bindings: link $path" }
                if (parts.isEmpty()) continue // "./" itself
                check(parts[0] == "uniffi") { "engine bindings: $path is outside uniffi/" }
                check(entry.isDirectory || (entry.isFile && parts.last().endsWith(".kt"))) { "engine bindings: $path is not a .kt file" }
            }
        }
        // Wiped first so no stale file stays.
        services.fs.delete { delete(kotlinDir) }
        services.fs.copy {
            from(services.archives.tarTree(services.archives.gzip(archive)))
            into(kotlinDir)
        }
    }
}

// srcDir of offline-translator's app/build.gradle.kts line 22 (java.srcDir(...generated/source/uniffi/kotlin)).
// AGP 9 (built-in Kotlin): the Kotlin source set's own `directories`, srcDir being deprecated.
android.sourceSets.getByName("main").jniLibs.directories.add(engineJniDir.get().asFile.path)
android.sourceSets.getByName("main").kotlin.directories.add(engineKotlinDir.get().asFile.path)
tasks.named("preBuild") { dependsOn(fetchEngine) }
