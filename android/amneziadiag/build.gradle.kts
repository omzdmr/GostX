plugins {
    id("com.android.application")
}

android {
    namespace = "com.gostx.amneziadiag"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gostx.amneziadiag"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
