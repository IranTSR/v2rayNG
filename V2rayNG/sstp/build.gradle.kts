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
            java.filter.include(
                "kittoku/osc/client/**",
                "kittoku/osc/cipher/**",
                "kittoku/osc/io/**",
                "kittoku/osc/unit/**",
                "kittoku/osc/extension/Byte.kt",
                "kittoku/osc/extension/ByteArray.kt",
                "kittoku/osc/extension/ByteBuffer.kt",
                "kittoku/osc/extension/Short.kt",
                "kittoku/osc/extension/String.kt",
                "kittoku/osc/configuration.kt"
            )
            java.filter.exclude("kittoku/osc/client/ppp/IpcpClient.kt")
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
