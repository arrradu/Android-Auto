plugins {
    id("com.android.application")
}

android {
    compileSdk = 36
    namespace = "com.arrradu.aa900.installer"

    defaultConfig {
        applicationId = "com.arrradu.aa900.installer"
        minSdk = 26
        targetSdk = 34
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 1000 + ciRun
        versionName = "1.0.$ciRun"
    }

    signingConfigs {
        getByName("debug") {
            // The same fixed key as the car app, so each build installs over the last.
            storeFile = rootProject.file("aa-lynk-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}
