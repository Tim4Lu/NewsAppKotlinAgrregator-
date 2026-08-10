import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Закодовані значення ключів для безпеки
val geminiKey1 = "QVEuQWI4Uk42S053VUs5WjN6ZDh2YzB4UFR1RmNjVVctNlhZQTEyUlltUTB6V29vcmwwTnc="
val geminiKey2 = "QVEuQWI4Uk42Sk1Pb1FrSWFETzVUSEdsS0pJNC13WW4yUjhyS0s2YzY4NVRZRUVLN0V1bnc="
val groqKey = "Z3NrX2xLbGQydVYxZ1ZyWGxLdGd3RnhXR2R5YjJZU3NJbENCVjcyV1VhaGs5SnAxdE96dlFh"
val openAiKey = "c2stcHJvai1kUXJZTUF6emtWRXZDNGdaWXRfTldXQm1mSTZldTFfWkhTZGl6dVhLVE5rN0hZbHZGS2pINW9nX1F6NjZuMDM0LS1xRVpvdnVFVDNCbGtGSkdsQ0xiU28tTzVONUZ4a2FHbVhuQzJwZWh0cS1VV2s3eWZlYXFHUDRmOGtjOGNaR1JVUl8zc3RJVkJhbldfbmlqSEF3TkM3blVB"

android {
    namespace = "com.newsapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.newsapp"
        minSdk = 24
        targetSdk = 35
        versionCode 1
        versionName "1.0"

        // Передаємо закодовані ключі в BuildConfig
        buildConfigField("String", "GEMINI_1", "\"$geminiKey1\"")
        buildConfigField("String", "GEMINI_2", "\"$geminiKey2\"")
        buildConfigField("String", "GROQ", "\"$groqKey\"")
        buildConfigField("String", "OPENAI", "\"$openAiKey\"")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    
    // Ktor Client
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
}
