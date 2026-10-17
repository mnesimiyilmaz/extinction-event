plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.mnesimiyilmaz.extinction"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mnesimiyilmaz.extinction"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json) // android.jar's org.json is stubbed in JVM tests
    // On-device GL checks: the real driver and shader compiler of the phone under test.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
