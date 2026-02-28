plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.example.mixtape"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.mixtape"
        minSdk = 26
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
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.recyclerview)

    //Glide:
    implementation(libs.glide)

    //firebase:
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    //Auth UI:
    implementation(libs.firebase.ui.auth)

    //Realtime DB:
    //Docs: https://firebase.google.com/docs/database/android/start
    //Rules: https://firebase.google.com/docs/database/security
    implementation(libs.firebase.database)

    //Storage:
    implementation(libs.firebase.storage)

    //Firestore:
    implementation(libs.firebase.firestore)
}