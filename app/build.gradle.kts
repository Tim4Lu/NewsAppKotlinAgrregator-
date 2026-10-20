import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun getSecret(key: String): String {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) load(file.inputStream())
    }
    val rawValue = System.getenv(key) ?: localProperties.getProperty(key) ?: ""
    return rawValue.replace("\r", "").replace("\n", "").trim()
}

android {
    namespace = "com.newsapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.newsapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 460
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${getSecret("TELEGRAM_BOT_TOKEN")}\"")
        buildConfigField("String", "GEMINI_KEYS", "\"${getSecret("GEMINI_KEYS")}\"")
        buildConfigField("String", "SCRAPER_API_KEY", "\"${getSecret("SCRAPER_API_KEY")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${getSecret("GROQ_API_KEY")}\"")
    }

    signingConfigs {
        create("release") {
            val envPath = System.getenv("KEYSTORE_PATH")
            val ksFile = if (!envPath.isNullOrEmpty()) file(envPath) else file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = getSecret("KEYSTORE_PASSWORD")
                keyAlias = getSecret("KEY_ALIAS")
                keyPassword = getSecret("KEY_PASSWORD")
            } else {
                throw GradleException("Release keystore not found. Set KEYSTORE_PATH env or place release.keystore in project root.")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    
    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
