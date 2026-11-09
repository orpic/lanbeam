plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lanbeam"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lanbeam"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
    }

    // Release signing. The key and its passwords never live in this repo: they come from
    // ~/.gradle/gradle.properties (or -P / ORG_GRADLE_PROJECT_ env vars). Without them, release
    // builds are left unsigned, so a fresh clone still builds.
    val storeFilePath = providers.gradleProperty("LANBEAM_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) create("release") {
            storeFile = file(storeFilePath)
            storePassword = providers.gradleProperty("LANBEAM_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("LANBEAM_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("LANBEAM_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/BCKEY.*")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("com.google.zxing:core:3.5.3")
}
