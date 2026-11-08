plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val wifiWrenchVersionCode = providers.gradleProperty("wifi.wrench.versionCode")
    .orElse("2")
    .get()
    .toInt()
require(wifiWrenchVersionCode in 2..2_100_000_000) { "wifi.wrench.versionCode 超出合法范围" }

android {
    namespace = "com.chuankangkk.wrenchlift"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.chuankangkk.wrenchlift"
        minSdk = 26
        targetSdk = 35
        versionCode = wifiWrenchVersionCode
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI 推 main 时解码仓库里的固定签名（密钥只在 CI 临时目录，不进仓库）；调试版和正式版都用它签，覆盖安装不冲突。
    // 没有这两个环境变量（本机、PR 构建）时调试版用默认调试签名，正式版不签名。
    val stableKeyPath = providers.environmentVariable("WIFI_WRENCH_DEBUG_KEYSTORE").orNull
    val stableKeyPassword = providers.environmentVariable("WIFI_WRENCH_DEBUG_STORE_PASSWORD").orNull
    val stableSigning = !stableKeyPath.isNullOrBlank() && !stableKeyPassword.isNullOrBlank()
    if (stableSigning) {
        signingConfigs.create("stable") {
            storeFile = file(stableKeyPath!!)
            storeType = "PKCS12"
            storePassword = stableKeyPassword
            keyAlias = "wifi-wrench-debug"
            keyPassword = stableKeyPassword
        }
    }

    buildTypes {
        debug {
            // 调试版：带「调试工具」入口，只给开发自己用。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug.$wifiWrenchVersionCode"
            if (stableSigning) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // 正式版：发给别人用。没有调试入口、没有任何调试 / 模拟字样，版本号就是 0.3.0。
            if (stableSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    ksp("androidx.room:room-compiler:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    // Robolectric 原生图形下跑 Compose：工人页交互断言 + 截图证据（不需要模拟器）。
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // 仅测试：用 POI 读回导出的 xlsx，核对全量行数与原生图表引用（App 运行时不依赖 POI）。
    testImplementation("org.apache.poi:poi-ooxml:5.4.1")
}
