plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // RecyclerView — used by ClipboardPanel for horizontal clip chip list
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room — local SQLite database (drafts + clipboard history + personal words)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")         // Adds suspend/coroutine support
    ksp("androidx.room:room-compiler:$roomVersion")              // Generates DB code automatically

    // Coroutines — for background saving, gesture decoding, prediction, clipboard
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
