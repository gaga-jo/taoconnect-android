plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gaius.taoconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gaius.taoconnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Reconnaissance optique du chinois simplifie/traditionnel.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Traduction chinois -> francais sur l'appareil apres telechargement des modeles.
    implementation("com.google.mlkit:translate:17.0.3")
}
