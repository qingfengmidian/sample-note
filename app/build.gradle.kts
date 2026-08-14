plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.sample.docreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sample.docreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric 单测需能访问 debug assets 中的 fixtures
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // Compose（BOM 统一版本）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation / Lifecycle
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // 存储
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // M3: Compose UI Test on Robolectric（TC-UI-01/02/03），BOM 管版本
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    // 仪器测试（espresso 3.7.0 起兼容新版 Android 移除 InputManager.getInstance 的变更）
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// M1: 测试夹具生成（buildSrc/FixtureGenerator），产物不入库
val generateFixtures = tasks.register("generateFixtures") {
    group = "build"
    description = "生成测试夹具到 test resources 与 debug assets"
    outputs.dir("src/test/resources/fixtures")
    outputs.dir("src/debug/assets/fixtures")
    doLast {
        FixtureGenerator.generate(file("src/test/resources/fixtures"))
        FixtureGenerator.generate(file("src/debug/assets/fixtures"))
    }
}
// M1 验证：fixtures 校验 task，确认样本编码与结构符合《测试用例》§0 描述
val validateFixtures = tasks.register("validateFixtures") {
    group = "verification"
    description = "校验 fixtures 编码与结构符合《测试用例》§0 描述"
    dependsOn(generateFixtures)
    doLast {
        FixtureGenerator.validate(file("src/test/resources/fixtures"))
    }
}
tasks.matching { it.name == "preBuild" || it.name == "testDebugUnitTest" || it.name == "connectedDebugAndroidTest" }
    .configureEach { dependsOn(generateFixtures) }
tasks.named("check") { dependsOn(validateFixtures) }
