plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "io.tapper.firetv"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.tapper.firetv"
        // API 25 = Fire OS 6, which covers the Fire TV Stick 4K. Raising this to
        // 26 would drop that device. It is also why the app ships static font
        // files rather than the variable Archivo TTF: FontVariation needs 26.
        minSdk = 25
        targetSdk = 34
        versionCode = 6
        versionName = "0.5.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    // Keystore-backed storage for Xtream credentials. They are embedded in every
    // stream URL, so a plaintext copy on disk is a resellable subscription.
    implementation(libs.security.crypto)
}
