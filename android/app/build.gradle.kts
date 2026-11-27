import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun readConfigValue(key: String): String {
    return (System.getenv(key) ?: localProps.getProperty(key) ?: "").trim()
}

fun escapeBuildConfigString(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.emofah.agencies"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emofah.agencies"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val supabaseUrl = readConfigValue("SUPABASE_URL").ifBlank { "https://nbmmfdiqywmkjaltezro.supabase.co" }
        val rawPublishableKey = readConfigValue("SUPABASE_PUBLISHABLE_KEY")
        val rawLegacyJwt = readConfigValue("SUPABASE_LEGACY_JWT")
        val rawAnonKey = readConfigValue("SUPABASE_ANON_KEY")

        val supabasePublishableKey = rawPublishableKey.ifBlank {
            if (rawAnonKey.startsWith("sb_publishable_")) rawAnonKey else ""
        }.ifBlank {
            "sb_publishable_IYNhHXjZ1lPPdRFIpKuonA_ydMHV4t3"
        }

        val supabaseLegacyKey = rawLegacyJwt.ifBlank {
            if (rawAnonKey.startsWith("eyJ")) rawAnonKey else ""
        }

        val supabaseAnonKey = if (rawAnonKey.isNotBlank()) rawAnonKey else supabasePublishableKey

        val publicStorageBaseUrl = readConfigValue("PUBLIC_STORAGE_BASE_URL").ifBlank {
            "https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/"
        }
        val cloudRecognitionUrl = readConfigValue("CLOUD_RECOGNITION_URL")
        buildConfigField("String", "SUPABASE_URL", "\"${escapeBuildConfigString(supabaseUrl)}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${escapeBuildConfigString(supabaseAnonKey)}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${escapeBuildConfigString(supabasePublishableKey)}\"")
        buildConfigField("String", "SUPABASE_LEGACY_JWT", "\"${escapeBuildConfigString(supabaseLegacyKey)}\"")
        buildConfigField("String", "PUBLIC_STORAGE_BASE_URL", "\"${escapeBuildConfigString(publicStorageBaseUrl)}\"")
        buildConfigField("String", "CLOUD_RECOGNITION_URL", "\"${escapeBuildConfigString(cloudRecognitionUrl)}\"")
    }

    val keystoreFile = file("$rootDir/release.keystore")
    val requireReleaseSigning = System.getenv("EMOFAH_REQUIRE_RELEASE_SIGNING") == "true"
    val releaseStorePassword = (System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: localProps.getProperty("ANDROID_KEYSTORE_PASSWORD"))?.trim()
    val releaseKeyAlias = (System.getenv("ANDROID_KEY_ALIAS") ?: localProps.getProperty("ANDROID_KEY_ALIAS"))?.trim()
    val releaseKeyPassword = (System.getenv("ANDROID_KEY_PASSWORD") ?: localProps.getProperty("ANDROID_KEY_PASSWORD"))?.trim()

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (requireReleaseSigning) {
                if (!keystoreFile.exists() || releaseStorePassword.isNullOrBlank() || releaseKeyAlias.isNullOrBlank() || releaseKeyPassword.isNullOrBlank()) {
                    throw org.gradle.api.GradleException("[RELEASE_SIGNING_FAILURE] Release signing is required but keystore or signing credentials are missing.")
                }
                signingConfig = signingConfigs.getByName("release")
            } else if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    val releasePackagingTasks = setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease",
        "signReleaseBundle",
        "validateSigningRelease",
    )
    gradle.taskGraph.whenReady {
        val runsReleasePackaging = allTasks.any { task ->
            task.project == project && task.name in releasePackagingTasks
        }
        if (runsReleasePackaging) {
            if (!keystoreFile.exists() || releaseStorePassword.isNullOrBlank() || releaseKeyAlias.isNullOrBlank() || releaseKeyPassword.isNullOrBlank()) {
                throw org.gradle.api.GradleException("[RELEASE_SIGNING_FAILURE] Release packaging requires release.keystore and signing credentials (ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD). Unsigned release builds are not permitted.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all { test ->
            test.testLogging {
                events("FAILED", "SKIPPED", "STANDARD_ERROR")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    val roomVersion = "2.6.1"
    val cameraXVersion = "1.4.0"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:1.1.1")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("printAppMetadata") {
    group = "help"
    description = "Prints authoritative application metadata configured in :app"
    val appId = android.defaultConfig.applicationId ?: ""
    val verCode = android.defaultConfig.versionCode ?: 0
    val verName = android.defaultConfig.versionName ?: ""
    val compileSdkVal = android.compileSdk ?: 0
    val targetSdkVal = android.defaultConfig.targetSdk ?: 0
    val minSdkVal = android.defaultConfig.minSdk ?: 0
    doLast {
        println("APPLICATION_ID=$appId")
        println("VERSION_CODE=$verCode")
        println("VERSION_NAME=$verName")
        println("COMPILE_SDK=$compileSdkVal")
        println("TARGET_SDK=$targetSdkVal")
        println("MIN_SDK=$minSdkVal")
    }
}

