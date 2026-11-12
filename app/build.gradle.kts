plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wardchart.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wardchart.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    // One key for every build, debug or release, from Android Studio or from
    // GitHub Actions. Same key = each new APK installs over the old one and
    // keeps its data. The password is deliberately not secret: this key's only
    // job is update continuity on a sideloaded personal app.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/ward-chart.jks")
            storePassword = "wardchart"
            keyAlias = "wardchart"
            keyPassword = "wardchart"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
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

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "ward-chart-${versionName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
