import org.gradle.api.GradleException

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

val supportedChaquopyVersions = listOf("3.14", "3.13", "3.12", "3.11", "3.10")

fun parsePythonVersion(output: String): String? =
    Regex("""Python (\d+\.\d+)(?:\.\d+)?""")
        .find(output)
        ?.groupValues
        ?.get(1)

fun probePythonCommand(command: List<String>): String? {
    val process = runCatching {
        ProcessBuilder(command + "--version")
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return null

    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.waitFor() != 0) {
        return null
    }

    return parsePythonVersion(output)
        ?.takeIf { it in supportedChaquopyVersions }
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
        val version = probePythonCommand(candidate) ?: continue
        return ChaquopyBuildPython(version = version, command = candidate)
    }

    throw GradleException(
        "Couldn't find a supported Python for Chaquopy. Looked for " +
            candidates.joinToString { it.joinToString(" ") } +
            "."
    )
}

val chaquopyBuildPython = detectChaquopyBuildPython()

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
}

chaquopy {
    defaultConfig {
        version = chaquopyBuildPython.version
        buildPython(*chaquopyBuildPython.command.toTypedArray())

        pip {
            install("yt-dlp")
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
