plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "kittoku.osc"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../../third_party/Open-SSTP-Client/app/src/main/java")
            java.exclude(
                "kittoku/osc/SharedBridge.kt",
                "kittoku/osc/control/**",
                "kittoku/osc/terminal/**",
                "kittoku/osc/service/**",
                "kittoku/osc/activity/**",
                "kittoku/osc/fragment/**",
                "kittoku/osc/preference/**",
                "kittoku/osc/extension/SharedPreferences.kt",
                "kittoku/osc/extension/View.kt",
                "kittoku/osc/debug/Capture.kt",
                "kittoku/osc/client/ppp/IpcpClient.kt"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
}
