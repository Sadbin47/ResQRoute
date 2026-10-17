import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------
// Release signing.
//
// Credentials live in a git-ignored `keystore.properties` at the repository
// root, so no key file and no password is ever committed:
//
//     storeFile=resqroute-release.jks      # absolute, or relative to the repo root
//     storePassword=…
//     keyAlias=…
//     keyPassword=…
//
// When the file is absent the release build still succeeds and simply produces
// an unsigned APK (Android refuses to install it). Debug builds and every test
// are unaffected by this block.
// ---------------------------------------------------------------------------
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseSigningAvailable = releaseKeystorePropertiesFile.isFile
val releaseKeystoreProperties = Properties().apply {
    if (releaseSigningAvailable) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.resqroute"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.resqroute"
        minSdk = 24
        targetSdk = 36
        // 2.0.0 is the V2 build (multi-factor risk). The debug-only test hooks are
        // stripped from release automatically (BuildConfig.DEBUG folds to false).
        // versionCode stays monotonic across the 1.0.0 submission build.
        versionCode = 20
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking stays off: the release already drops the debug-only
            // hooks (verified in the packaged dex), and R8 would add keep-rule
            // risk to Room/osmdroid this close to submission.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        // Needed for BuildConfig.DEBUG, which gates the debug-only test hooks.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        // The data layer is JVM-testable; android.util.Log calls become no-ops
        // in local unit tests instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
        // Robolectric needs merged resources to build a real Room database on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // Export the schema so migrations can be reviewed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Pinned to versions compatible with AGP 8.13.2 / compileSdk 36.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Phase 1 spike use only; the real protocol (Phase 8) replaces the adapter impl.
    // Pinned: 19.5.1 ships Kotlin 2.4 metadata, newer than this project's 2.2 toolchain.
    implementation("com.google.android.gms:play-services-nearby:19.1.0")

    // Phase 5: offline vector rendering host. Upstream is archived (Nov 2024), so it is
    // isolated behind OsmdroidMapAdapter and configured with a no-downloader tile provider.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Phase 6: durable hazard event log. Room owns hazard truth; the repository is the
    // only component allowed to write it.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Phase 6 tests run a real Room database on the JVM.
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
