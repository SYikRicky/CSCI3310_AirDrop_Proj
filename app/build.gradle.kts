plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)     // Firebase / Google Services
}

android {
    namespace = "com.example.csci3310_airdrop_proj"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.csci3310_airdrop_proj"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.fragment)
    implementation(libs.lifecycle.service)
    implementation(libs.nearby.connections)
    implementation(libs.play.services.location)
    implementation(libs.osmdroid)

    // Firebase — versions managed by BOM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
