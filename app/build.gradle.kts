import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metrowin.keyboard"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.metrowin.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // The key of the official release comes from keystore.properties in the root of the project (never committed): storeFile,
    // storePassword, keyAlias and keyPassword. Without it the release build is unsigned, which is what F-Droid wants; for a test
    // build that installs over the debug build, pass -PsignWithDebugKey=true.
    val keystoreFile = rootProject.file("keystore.properties")
    val official = keystoreFile.takeIf { it.exists() }?.let { file ->
        val keys = Properties().apply { file.inputStream().use { load(it) } }
        signingConfigs.create("official") {
            storeFile = rootProject.file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Not shrunk: the settings screens are created by name from XML, and a keyboard that fails on a renamed class is worth
            // more than a smaller file (the app is a few MB).
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = when {
                official != null -> official
                providers.gradleProperty("signWithDebugKey").orNull == "true" -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // F-Droid style: no dependency metadata blob inside the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the merged resources and assets to run the keyboard.
            isIncludeAndroidResources = true
        }
    }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
