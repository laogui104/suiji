import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, def: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: def)

android {
    namespace = "com.suiji.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.suiji.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.2.0"

        buildConfigField("String", "LLM_PROVIDER", "\"${secret("LLM_PROVIDER", "deepseek")}\"")
        buildConfigField("String", "LLM_BASE_URL", "\"${secret("LLM_BASE_URL", "")}\"")
        buildConfigField("String", "LLM_MODEL", "\"${secret("LLM_MODEL", "")}\"")
        buildConfigField("String", "LLM_API_KEY", "\"${secret("LLM_API_KEY", "")}\"")

        // 离线识别只保留 arm64（真机全是 arm64）；debug 另带 x86_64 以便模拟器调试
        ndk { abiFilters += listOf("arm64-v8a") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            // 未配置签名时用 debug 签名，保证 release 变体也能直接安装测试
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // 离线模型是 zip，已经是压缩格式，再压一遍只会拖慢安装
        noCompress += listOf("zip")
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module"
        )
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.window:window:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 离线语音识别：不依赖任何厂商服务、不需要 Key，保证「点了就一定能转成文字」
    implementation("com.alphacephei:vosk-android:0.3.47")

    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 是空壳，这里补一份真实现
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
