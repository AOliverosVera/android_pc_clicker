import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing key, kept OUTSIDE the repository (scripts/build.ps1 creates
// it on first use). Override the location with -Ptouchpad.signing=<file>.
val signingFile = file(
    providers.gradleProperty("touchpad.signing")
        .getOrElse("${System.getProperty("user.home")}/.touchpad/signing.properties"),
)
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use(::load) }

android {
    namespace = "dev.playground.touchpad"
    // Current androidx libraries require compiling against API 37.2.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.playground.touchpad"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release builds run Compose noticeably smoother than debug builds,
            // which matters for pointer latency.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A private key, never the shared debug key. Without the signing
            // file the release APK is left unsigned (and won't install).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

tasks.withType<Test>().configureEach {
    // Crypto vectors shared with the PC server and computed independently.
    val vectors = rootProject.file("../docs/test-vectors.txt")
    inputs.file(vectors)
    systemProperty("touchpad.vectors", vectors.absolutePath)
    // Opt-in test against a running server (scripts/interop-test.ps1).
    providers.gradleProperty("touchpadInteropHost").orNull?.let {
        systemProperty("touchpad.interop.host", it)
        outputs.upToDateWhen { false }
    }
    providers.gradleProperty("touchpadInteropCodeFile").orNull?.let { systemProperty("touchpad.interop.codefile", it) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
