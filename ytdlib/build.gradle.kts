import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.chaquopy.python)
    `maven-publish`
}

data class ChaquopyBuildPython(
    val version: String,
    val command: List<String>,
)

val supportedChaquopyVersions = listOf("3.13")

fun parsePythonVersion(output: String): String? =
    Regex("""Python (\d+\.\d+)(?:\.\d+)?""")
        .find(output)
        ?.groupValues
        ?.get(1)

fun probePythonCommand(command: List<String>): ChaquopyBuildPython? {
    val process = runCatching {
        ProcessBuilder(command + "--version")
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return null

    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() != 0) {
        return null
    }

    val version = parsePythonVersion(output)
        ?.takeIf { it in supportedChaquopyVersions }
        ?: return null

    // Some Python distributions are exposed through a symlink. Passing that symlink to
    // venv may record the symlink directory as Python's home and create a broken environment.
    val executableProcess = runCatching {
        ProcessBuilder(
            command + listOf(
                "-c",
                "import os, sys; print(os.path.realpath(sys.executable))",
            )
        )
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return null
    val executable = executableProcess.inputStream.bufferedReader().use { it.readText().trim() }
    if (executableProcess.waitFor() != 0 || executable.isBlank()) {
        return null
    }

    return ChaquopyBuildPython(version = version, command = listOf(executable))
}

fun detectChaquopyBuildPython(): ChaquopyBuildPython {
    val envCandidate = providers.environmentVariable("CHAQUOPY_BUILD_PYTHON")
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { listOf(it) }

    val candidates = listOfNotNull(
        envCandidate,
        listOf("python3"),
        listOf("python"),
    ) + supportedChaquopyVersions.map { version ->
        listOf("python$version")
    }

    for (candidate in candidates) {
        return probePythonCommand(candidate) ?: continue
    }

    throw GradleException(
        "Couldn't find a supported Python for Chaquopy. Looked for " +
            candidates.joinToString { it.joinToString(" ") } +
            "."
    )
}

val chaquopyBuildPython = detectChaquopyBuildPython()
val quickJsBuildScript = rootProject.file("scripts/build-android-quickjs.sh")
val quickJsWorkDir = layout.buildDirectory.dir("quickjs")
val quickJsJniLibsDir = layout.buildDirectory.dir("generated/quickjs/jniLibs")
val quickJsExecutable = quickJsJniLibsDir.map { it.file("arm64-v8a/libqjs.so") }

android {
    namespace = "com.mzgs.ytdlib"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c11")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Provision CMake in clean CI environments before buildBundledQuickJs runs.
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    sourceSets {
        getByName("main").jniLibs.srcDir(quickJsJniLibsDir)
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val buildBundledQuickJs by tasks.registering(Exec::class) {
    description = "Builds the bundled QuickJS-NG executable for Android ARM64."
    group = "build"

    inputs.file(quickJsBuildScript)
    inputs.property("quickJsVersion", "0.15.1")
    outputs.file(quickJsExecutable)

    doFirst {
        quickJsExecutable.get().asFile.parentFile.mkdirs()
    }

    commandLine(
        "bash",
        quickJsBuildScript.absolutePath,
        android.ndkDirectory.absolutePath,
        quickJsExecutable.get().asFile.absolutePath,
        quickJsWorkDir.get().asFile.absolutePath,
    )
}

// AGP provisions the pinned SDK CMake package when these tasks run. Make that happen before
// the standalone QuickJS build, including on clean JitPack images which have no system CMake.
buildBundledQuickJs.configure {
    dependsOn(tasks.matching { it.name.startsWith("configureCMake") })
}

tasks.configureEach {
    if (
        name.startsWith("merge") &&
        (name.endsWith("NativeLibs") || name.endsWith("JniLibFolders"))
    ) {
        dependsOn(buildBundledQuickJs)
    }
}

chaquopy {
    defaultConfig {
        version = chaquopyBuildPython.version
        buildPython(*chaquopyBuildPython.command.toTypedArray())
        extractPackages("*")

        pip {
            // curl_cffi's Android wheel declares cffi>=2.0, while Chaquopy currently ships
            // Android cffi 1.17.1 for Python 3.13. Install the compatible wheel set directly.
            options("--no-deps")
            install("yt-dlp==2026.08.19")
            install("yt-dlp-ejs==0.8.0")
            install("pycparser")
            install("certifi")
            install("cffi==1.17.1")
            install("curl_cffi==0.15.0")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("ytdlib")
                description.set("Android library for yt-dlp integration and MP3 conversion.")
            }
        }
    }
}
