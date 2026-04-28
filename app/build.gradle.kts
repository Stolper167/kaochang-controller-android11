plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val kaochangKeystoreFile = rootProject.file("kaochang.jks")
val kaochangStorePassword = providers.gradleProperty("KAOCHANG_STORE_PASSWORD").orNull
    ?: System.getenv("KAOCHANG_STORE_PASSWORD")
val kaochangKeyAlias = providers.gradleProperty("KAOCHANG_KEY_ALIAS").orNull
    ?: System.getenv("KAOCHANG_KEY_ALIAS")
val kaochangKeyPassword = providers.gradleProperty("KAOCHANG_KEY_PASSWORD").orNull
    ?: System.getenv("KAOCHANG_KEY_PASSWORD")
val hasLocalKaochangSigning =
    kaochangKeystoreFile.exists() &&
        !kaochangStorePassword.isNullOrBlank() &&
        !kaochangKeyAlias.isNullOrBlank() &&
        !kaochangKeyPassword.isNullOrBlank()

android {
    namespace = "cn.niuyannet.kaochang.android"
    compileSdk = 34

    signingConfigs {
        if (hasLocalKaochangSigning) {
            create("kaochang") {
                storeFile = kaochangKeystoreFile
                storePassword = kaochangStorePassword
                keyAlias = kaochangKeyAlias
                keyPassword = kaochangKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "cn.niuyannet.kaochang.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 56
        versionName = "1.3.01"
        ndk {
            moduleName = "jssc"
            abiFilters.add("armeabi-v7a")
//            abiFilters.add("x86")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("kaochang") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("kaochang") ?: signingConfigs.getByName("debug")
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
        dataBinding = true
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    buildToolsVersion = "35.0.0"
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(project(":libausbc"))

    implementation("io.github.justson:agentweb-core:v5.1.1-androidx")
    implementation("io.github.justson:agentweb-filechooser:v5.1.1-androidx")
    implementation("com.github.Justson:Downloader:v5.0.4-androidx")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3")

    implementation("androidx.camera:camera-core:1.3.2")
    implementation("androidx.camera:camera-camera2:1.3.2")
    implementation("androidx.camera:camera-lifecycle:1.3.2")
    implementation("androidx.camera:camera-view:1.3.2")
    implementation(libs.androidx.lifecycle.service)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.alibaba:fastjson:1.2.76")
    implementation("com.lzy.net:okgo:3.0.4")

    implementation("com.afollestad.material-dialogs:input:3.3.0")
    implementation("com.afollestad.material-dialogs:bottomsheets:3.3.0")
    implementation("com.afollestad.material-dialogs:lifecycle:3.3.0")

    implementation("com.github.kongqw:AndroidSerialPort:1.0.0") {
        exclude(group = "com.android.support", module = "support-v4")
        exclude(group = "com.android.support", module = "design")
    }
    implementation("com.github.getActivity:XXPermissions:20.0")
    implementation("com.blankj:utilcodex:1.31.1")

    implementation("com.google.zxing:core:3.3.3")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("com.google.android.material:material:1.9.0")

    implementation("com.tencent.bugly:crashreport:latest.release")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.4.32")
    implementation("com.youth.banner:banner:2.1.0")
    implementation("com.shuyu:GSYVideoPlayer:7.1.8")

    implementation("com.quickbirdstudios:opencv:4.1.0-contrib")
}
