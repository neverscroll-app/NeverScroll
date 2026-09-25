import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningFile = file(
    providers.gradleProperty("neverscrollSigningFile").orNull
        ?: "${System.getProperty("user.home")}/.config/neverscroll/release.properties"
)
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use(::load)
}

android {
    namespace = "org.neverscroll.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.neverscroll.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.5"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningFile.isFile) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
