plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // kapt is needed so Room can generate database code at compile time
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.draftkeys.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.draftkeys.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // RecyclerView — used by ClipboardPanel for horizontal clip chip list
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room — local SQLite database (drafts + clipboard history + personal words)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")         // Adds suspend/coroutine support
    kapt("androidx.room:room-compiler:$roomVersion")              // Generates DB code automatically

    // Coroutines — for background saving, gesture decoding, prediction, clipboard
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
