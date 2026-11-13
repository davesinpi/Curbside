import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (git-ignored). Without it, release builds are unsigned.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.curbside"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.curbside"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }
    signingConfigs {
        if (!keystoreProps.isEmpty) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    // False positive: AGP 8.11 lint can't resolve CarAppService's hierarchy against compileSdk 37
    // ("must extend android.app.Service"). Drop once AGP officially supports SDK 37. (Same as PrivaCar.)
    lint { disable += "Instantiatable" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Budget (design section 5): car.app only, plus the Android Auto host glue. No AppCompat/Material/Compose.
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    testImplementation("junit:junit:4.13.2")
}
