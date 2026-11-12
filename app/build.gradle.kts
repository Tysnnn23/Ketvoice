import java.io.File
import java.util.Base64


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Discord Social SDK is owner-supplied and never redistributed through Git.
// Without the AAR the app still builds; the Discord integration is compiled out
// and the account UI stays hidden.
val discordSdk = file("libs/discord/discord_partner_sdk.aar")
val discordSdkAvailable = discordSdk.isFile &&
    !providers.gradleProperty("ketvoiceWithoutDiscord").map { it.equals("true", ignoreCase = true) }.getOrElse(false)

android {
    namespace = "com.ketvoice.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ketvoice.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 89
        versionName = "4.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("fr", "en", "hi", "ja", "zh", "ru", "it", "es", "de", "pt", "ko")
        ndkVersion = "27.0.12077973"
        if (discordSdkAvailable) {
            // Presence and account linking only need arm64 on phones; Quest keeps
            // x86_64 for the emulator via its flavor filters.
            ndk { abiFilters += listOf("arm64-v8a") }
            externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
        }
    }

    if (discordSdkAvailable) {
        externalNativeBuild {
            cmake {
                path = file("src/discord/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    if (discordSdkAvailable) {
        sourceSets["main"].java.srcDir("src/discord/kotlin")
    }

    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            buildConfigField("boolean", "IS_QUEST", "false")
        }
        create("quest") {
            dimension = "device"
            minSdk = 32
            targetSdk = 34
            buildConfigField("boolean", "IS_QUEST", "true")
            // Quest uses arm64; x86_64 permits the same panel build to be
            // exercised on an Android emulator without shipping 32-bit ABIs.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    signingConfigs {
        create("sideload") {
            // Priority order for the signing material:
            //  1) KETVOICE_KEYSTORE_B64 — base64 keystore body (GitHub secret, CI)
            //  2) KETVOICE_KEYSTORE     — path to a keystore file (local dev)
            //  3) fallback              — the well-known debug keystore
            // A stable keystore is what prevents "app conflicts with an existing
            // package" — every CI run must sign with the SAME key.
            val b64 = providers.environmentVariable("KETVOICE_KEYSTORE_B64").orNull
            val customStore = providers.environmentVariable("KETVOICE_KEYSTORE").orNull

            val resolvedStoreFile = when {
                !b64.isNullOrBlank() -> {
                    val decoded = File(layout.buildDirectory.asFile.get(), "signing/ketvoice-release.jks")
                    decoded.parentFile.mkdirs()
                    // Secrets are often pasted with line wrapping; the strict
                    // decoder throws on any whitespace, so strip it first.
                    val body = b64.filterNot { it.isWhitespace() }
                    decoded.writeBytes(Base64.getDecoder().decode(body))
                    decoded
                }
                !customStore.isNullOrBlank() -> file(customStore)
                else -> file("${System.getProperty("user.home")}/.android/debug.keystore")
            }
            val usingFallback = b64.isNullOrBlank() && customStore.isNullOrBlank()

            storeFile = resolvedStoreFile
            storePassword = if (usingFallback) "android"
                else providers.environmentVariable("KETVOICE_STORE_PASSWORD").orNull
            keyAlias = if (usingFallback) "androiddebugkey"
                else (providers.environmentVariable("KETVOICE_KEY_ALIAS").orNull ?: "ketvoice")
            keyPassword = if (usingFallback) "android"
                else providers.environmentVariable("KETVOICE_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        debug {
            // Instrumented fixture tests must never replace or clear the owner's real app.
            applicationIdSuffix = ".qa"
        }
        release {
            signingConfig = signingConfigs.getByName("sideload")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        prefab = discordSdkAvailable
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    if (discordSdkAvailable) {
        implementation(files(discordSdk))
        implementation("androidx.browser:browser:1.8.0")
    }
    val composeBom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media:media:1.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.github.kyant0:backdrop:1.0.0-rc02")
    implementation("androidx.media3:media3-datasource:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
