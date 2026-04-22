pluginManagement {
    repositories {
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public/") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/jcenter") }
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://www.jitpack.io") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public/") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/jcenter") }
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "KaoChangAndroid"
include(":app")

include(":libausbc")            // UVC摄像头
include(":libuvc")              // UVC摄像头
include(":libnative")           // UVC摄像头
include(":libuvccommon")        // UVC摄像头
