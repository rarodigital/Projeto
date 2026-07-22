plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raro.controletv.receiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raro.controletv.receiver"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "2.5"
    }

    signingConfigs {
        // keystore fixa e comitada: builds do CI sempre assinam igual, então
        // atualizar o app não pede mais desinstalar antes (assinatura não muda a cada run).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    flavorDimensions += "home"
    productFlavors {
        // versão com launcher próprio (HOME), travado em paisagem — pro S9/DeX espelhando na TV
        create("launcher") {
            dimension = "home"
            versionNameSuffix = "-launcher"
            manifestPlaceholders["enableLauncherHome"] = "true"
            manifestPlaceholders["launcherOrientation"] = "landscape"
        }
        // versão sem launcher (mantém o launcher normal do aparelho) — uso como app comum
        create("normal") {
            dimension = "home"
            versionNameSuffix = "-normal"
            manifestPlaceholders["enableLauncherHome"] = "false"
            manifestPlaceholders["launcherOrientation"] = "unspecified"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // (receiver é app só de Views, sem Compose)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Player DLNA (CastPlayerActivity) + YouTube Lounge (JSONObject/JSONArray já vem no Android)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
