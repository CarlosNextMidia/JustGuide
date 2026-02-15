plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.milton.justguide"
    compileSdk {
        version = release(36)
    }
// COMANDO DE ATIVAÇÃO NECESSÁRIO!
    buildFeatures {
        viewBinding = true
    }
    defaultConfig {
        applicationId = "com.milton.justguide"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
signingConfigs {
    create("release") {
        val props = org.jetbrains.kotlin.konan.properties.Properties()
        val localProps = rootProject.file("local.properties")
        if (localProps.exists()) props.load(localProps.inputStream())
        storeFile = file(props.getProperty("RELEASE_STORE_FILE", ""))
        storePassword = props.getProperty("RELEASE_STORE_PASSWORD", "")
        keyAlias = props.getProperty("RELEASE_KEY_ALIAS", "")
        keyPassword = props.getProperty("RELEASE_KEY_PASSWORD", "")
    }
}
    buildTypes {
        release {
    isMinifyEnabled = true
    isShrinkResources = true
signingConfig = signingConfigs.getByName("release")
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {



    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // 🗺️ GOOGLE MAPS & LOCATION
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.android.libraries.places:places:3.3.0")
    implementation("com.google.code.gson:gson:2.10.1")




    // 📸 CâmeraX Core
    val camera_version = "1.3.1"
    implementation("androidx.camera:camera-core:$camera_version")
    implementation("androidx.camera:camera-camera2:$camera_version")
    implementation("androidx.camera:camera-lifecycle:$camera_version")
    implementation("androidx.camera:camera-view:$camera_version") // Para usar o PreviewView


    // 💾 ARMAZENAMENTO LOCAL (ROOM - Banco de Dados)
    implementation("androidx.room:room-runtime:2.6.1")
    // Use 'ksp' para o compilador (Pode ser que você tenha que adicionar o plugin KSP no 'build.gradle.kts (Project)')
    // Se der erro no 'ksp', troque por 'kapt' e adicione o plugin 'id("kotlin-kapt")' no topo do arquivo.
    // Vamos começar com o 'ksp' por ser o padrão atual:
    // (PODE PRECISAR DE CONFIGURAÇÃO EXTRA)
    // Se for MUITO complexo, ignore por agora, e voltamos para o Room depois.

    // 💉 ARQUITETURA MVVM & Coroutines (Para tarefas em segundo plano)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")

    // 🎨 Material Design (Visual 11/10)
    implementation("com.google.android.material:material:1.11.0")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)



    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}