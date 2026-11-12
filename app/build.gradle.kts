plugins { id("com.android.application") }

android {
    namespace = "com.flownote.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.flownote.mobile"
        minSdk = 29
        targetSdk = 35
        versionCode = providers.gradleProperty("testVersionCode").orNull?.toInt() ?: 13200
        versionName = providers.gradleProperty("testVersionName").orNull ?: "1.3.2"
    }
    signingConfigs {
        create("distribution") {
            val signingPath = System.getenv("FLOWNOTE_ANDROID_KEYSTORE")
            if (signingPath != null) {
                storeFile = file(signingPath)
                storePassword = System.getenv("FLOWNOTE_ANDROID_SIGNING_PASSWORD")
                keyAlias = "flownote-android"
                keyPassword = System.getenv("FLOWNOTE_ANDROID_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "WEB_URL", "\"https://flownote-react-staging.vercel.app\"")
        }
        release {
            check(!providers.gradleProperty("testVersionCode").isPresent && !providers.gradleProperty("testVersionName").isPresent || gradle.startParameter.taskNames.none { it.contains("Release", ignoreCase = true) }) { "Test version overrides are staging/debug only" }
            signingConfig = signingConfigs.getByName("distribution")
            buildConfigField("String", "WEB_URL", "\"https://flownote-react.vercel.app\"")
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
