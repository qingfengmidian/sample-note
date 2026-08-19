// S1 SAF Provider 技术探针（spike）：独立 application module，仅 debug 用途，不打 release 包。
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.sample.safprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sample.safprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "probe"
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
    implementation("androidx.activity:activity-ktx:1.9.3")
}
