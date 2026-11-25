import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.patschgoo.anibox"
    compileSdk {
        // libmpv 1.1.0 asks for compileSdk 37 or newer.
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.patschgoo.anibox"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"

        // Phones only: libmpv also ships x86 and x86_64, which only emulators need.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // The test key that lives in the repository (public on purpose, see README.md), so every
        // build can be installed over the previous one. Use your own key for anything you publish.
        create("test") {
            storeFile = file("anibox-test.keystore")
            storePassword = "anibox-test"
            keyAlias = "anibox-test"
            keyPassword = "anibox-test"
        }
        // Your own release key, used only when it is configured: environment variables (CI) or
        // gradle properties (~/.gradle/gradle.properties on your PC):
        //   ANIBOX_KEYSTORE_FILE or ANIBOX_KEYSTORE_BASE64, ANIBOX_KEYSTORE_PASSWORD,
        //   ANIBOX_KEY_ALIAS, ANIBOX_KEY_PASSWORD (the key password defaults to the store password)
        fun setting(name: String): String? =
            (System.getenv(name) ?: providers.gradleProperty(name).orNull)?.trim()?.takeIf { it.isNotEmpty() }
        val keystoreFile: File? = setting("ANIBOX_KEYSTORE_FILE")?.let { file(it) }
            ?: setting("ANIBOX_KEYSTORE_BASE64")?.let { b64 ->
                layout.buildDirectory.file("signing/release.jks").get().asFile.also {
                    it.parentFile.mkdirs()
                    it.writeBytes(Base64.getMimeDecoder().decode(b64))
                }
            }
        val storePass = setting("ANIBOX_KEYSTORE_PASSWORD")
        val alias = setting("ANIBOX_KEY_ALIAS")
        if (keystoreFile != null && storePass != null && alias != null) {
            create("own") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = setting("ANIBOX_KEY_PASSWORD") ?: storePass
            }
        } else if (keystoreFile != null || storePass != null || alias != null) {
            logger.warn("AniBox: incomplete own signing key (need the keystore, ANIBOX_KEYSTORE_PASSWORD and ANIBOX_KEY_ALIAS); using the test key.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Your own key when configured, else the test key.
            signingConfig = signingConfigs.findByName("own") ?: signingConfigs.getByName("test")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libraries keep the download small (Android unpacks them on install).
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric (screenshot tests) reads the app's strings and fonts.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // The app is English only, on purpose (like AniBox on the PC).
        disable += "MissingTranslation"
    }
}

// Screenshot tests (Robolectric + Roborazzi, in src/test/.../screenshots) render the Compose
// screens to PNGs on the JVM. They only run when asked for:
//   ./gradlew :app:testDebugUnitTest -Pscreenshots   -> app/build/outputs/roborazzi/
val screenshots: String? = providers.gradleProperty("screenshots").orNull
tasks.withType<Test>().configureEach {
    if (screenshots == null) {
        exclude("io/github/patschgoo/anibox/screenshots/**")
    } else {
        filter.includeTestsMatching("io.github.patschgoo.anibox.screenshots.*")
        systemProperty("roborazzi.test.record", "true")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("anibox.outDir", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.absolutePath)
        maxHeapSize = "3g"
        outputs.upToDateWhen { false }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    // The Shield's network shares (SMB 2/3); BouncyCastle gives Android the MD4 that NTLM sign-in needs.
    implementation(libs.smbj)
    implementation(libs.bouncycastle)
    // The player: mpv + FFmpeg (GPL-3.0-or-later), the same build the Findroid app uses.
    implementation(libs.libmpv)
    // Scanning the PC's QR code: CameraX (no Google Play services) + ZXing's decoder.
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
