import org.apache.tools.ant.taskdefs.condition.Os
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 34
    namespace = "com.jiangdg.uvccamera"

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
//            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        disable.add("MissingTranslation")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// 如果需要静态链接 请使用下面的方法编译.a静态库 并注释externalNativeBuild

//tasks.withType<JavaCompile>().configureEach {
//    dependsOn("ndkBuild")
//}
//
//fun getNdkBuildPath(): String {
//    var ndkBuildingDir = "D:/Android/Sdk/ndk/26.1.10909125"// System.getenv("NDK_HOME")
//    if (ndkBuildingDir.isNullOrEmpty()) {
//        val localProperties = Properties().apply {
//            load(File(rootProject.projectDir, "local.properties").inputStream())
//        }
//        ndkBuildingDir = localProperties.getProperty("ndk.dir")
//    }
//    return if (Os.isFamily(Os.FAMILY_WINDOWS)) {
//        "$ndkBuildingDir/ndk-build.cmd"
//    } else {
//        "$ndkBuildingDir/ndk-build"
//    }
//}
//
//tasks.register<Exec>("ndkBuild") {
//    println("executing ndkBuild")
//    val ndkBuildPath = getNdkBuildPath()
//    commandLine(ndkBuildPath, "-j8", "-C", file("src/main").absolutePath, "NDK_LIBS_OUT=${file("src/main/jniLibs").absolutePath}")
//}
//
//tasks.register<Exec>("ndkClean") {
//    println("executing ndkBuild clean")
//    val ndkBuildPath = getNdkBuildPath()
//    commandLine(ndkBuildPath, "clean", "-C", file("src/main").absolutePath)
//}
//
//tasks.named("clean") {
//    dependsOn("ndkClean")
//}

dependencies {
    implementation(project(":libuvccommon"))

    implementation(libs.androidx.appcompat)
    implementation(libs.xlog)
}