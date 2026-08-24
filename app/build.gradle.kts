plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.instinctazero.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.instinctazero.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    // This APK deliberately speaks to one dedicated, path-limited InstinctaZero gateway.
    // Keeping the origin fixed also makes the native URL allow-list a build-time invariant.
    val leelaGatewayOrigin = "https://rafael-ms-7e34.tail273ae6.ts.net:8443"
    defaultConfig {
        buildConfigField("String", "LEELA_GATEWAY_ORIGIN", "\"$leelaGatewayOrigin\"")
    }

    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("INSTINCTAZERO_STORE_FILE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("INSTINCTAZERO_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("INSTINCTAZERO_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("INSTINCTAZERO_KEY_PASSWORD").orNull
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (providers.gradleProperty("INSTINCTAZERO_STORE_FILE").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
