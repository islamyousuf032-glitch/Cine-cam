plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cinecam.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cinecam.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // MotionCam architecture: primarily C++ pipeline - ARM64 primary
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-fexceptions",
                    "-frtti",
                    "-O3",
                    "-Wall",
                    "-Wextra"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_TOOLCHAIN=clang"
                )
            }
        }

        buildConfigField("String", "GIT_COMMIT", "\"${getGitCommit()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${java.time.Instant.now()}\"")
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.1.10909125"

    // For future: Prefab, ML, RenderScript off
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

fun getGitCommit(): String {
    return try {
        val stdout = java.io.ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        stdout.toString().trim().ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Camera2 + CameraX (for capability query, preview fallback)
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")
    implementation("androidx.camera:camera-video:1.3.3")

    // ExoPlayer for preview? No - we use OpenGL; but keep Media3 for audio sync
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // DataStore for manual settings persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Serialization (for .mcraw header metadata JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coil for thumbnails
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
