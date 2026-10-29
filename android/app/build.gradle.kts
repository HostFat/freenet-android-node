plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")

android {
    namespace = "org.freenet.androidnode"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.freenet.androidnode"
        minSdk = 28
        targetSdk = 36
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 2
        versionName = project.findProperty("appVersionName") as String? ?: "0.1.1"

        testInstrumentationRunner = "org.freenet.androidnode.FreenetAndroidTestRunner"

        val abis = (project.findProperty("abis") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "x86_64")
        ndk {
            abiFilters += abis
        }

        val crashReportToken = (project.findProperty("crashReportToken") as String?)
            ?: System.getenv("CRASH_REPORT_TOKEN")
            ?: ""
        buildConfigField("String", "CRASH_REPORT_TOKEN", "\"${crashReportToken.replace("\"", "")}\"")
        val serviceReportToken = (project.findProperty("serviceReportToken") as String?)
            ?: System.getenv("SERVICE_REPORT_TOKEN")
            ?: ""
        buildConfigField(
            "String",
            "SERVICE_REPORT_TOKEN",
            "\"${serviceReportToken.replace("\"", "")}\"",
        )
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Test build: R8 disabled to check a Play Protect false positive.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.tomlj:tomlj:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // The android.jar org.json stubs throw in local unit tests.
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
