plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

val enableQualcommNpuRuntime = providers.gradleProperty("enableQualcommNpuRuntime").orNull == "true"

android {
    namespace = "com.mistbell.tavern.v3"
    compileSdk = 37

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.mistbell.tavern.v3"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "3.0.0-dev"

        // NPU device-test builds are arm64-only. Keep ordinary builds unrestricted so
        // x86_64 emulator tests and CPU/GPU development remain available.
        if (enableQualcommNpuRuntime) {
            ndk {
                abiFilters += setOf("arm64-v8a")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Native runtime payloads staged under app/src/main/jniLibs/arm64-v8a are copied
    // into the APK as real ELF libraries. Legacy packaging also makes the files available
    // under nativeLibraryDir for runtime loaders that require extracted .so files.
    packaging {
        jniLibs {
            useLegacyPackaging = enableQualcommNpuRuntime
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Opt-in staging of the proprietary/local QAIRT payload and the matching LiteRT Qualcomm
// dispatch plugin. Normal CI/builds remain usable without a local SDK; when enabled, preBuild
// runs the strict allowlist/checksum staging script before Android packages jniLibs.
val packageQualcommNpuRuntime =
    tasks.register<Exec>("packageQualcommNpuRuntime") {
        onlyIf { enableQualcommNpuRuntime }
        workingDir(rootProject.projectDir.parentFile)
        commandLine("bash", ".github/scripts/package-litertlm-qualcomm-runtime.sh")
    }

tasks.named("preBuild").configure {
    dependsOn(packageQualcommNpuRuntime)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
}
