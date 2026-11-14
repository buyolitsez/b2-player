// Bump when shipping notable features. The third number is the commit count, see versionCode.
val baseVersion = "0.5"
// Commit count of HEAD: grows with every merge to main, same locally and in CI (checkout needs full history).
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.buyolitsez.b2player"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.buyolitsez.b2player"
        minSdk = 29
        targetSdk = 36
        versionCode = commitCount
        versionName = "$baseVersion.$commitCount"
        ndk { abiFilters += "arm64-v8a" }
    }
    // CI signs with the Mac's debug key from a file named by DEBUG_KEYSTORE_FILE, so the phone accepts updates.
    signingConfigs { getByName("debug") { System.getenv("DEBUG_KEYSTORE_FILE")?.let { storeFile = file(it) } } }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The Claude SDK's dependencies ship the same license and manifest files.
    packaging { resources { excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val media3 = "1.9.0"
dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1") // ALAC and other codecs the phone lacks
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("sh.calvin.reorderable:reorderable:2.5.1")
    implementation("androidx.palette:palette:1.0.0")
    implementation("dev.chrisbanes.haze:haze-android:1.7.0") // blur behind the mini player and tabs
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.anthropic:anthropic-java:3.8.0")
}
