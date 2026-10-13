import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    if (File("app/google-services.json").exists()) {
        println("Enable gms in app plugins")
        alias(libs.plugins.gms)
        alias(libs.plugins.crashlytics)
    }
    id("kotlin-parcelize")
    id("crouter-plugin")
}

android {
    namespace = "cloud.music"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "cloud.music"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()

        multiDexEnabled = true

        ndk {
            abiFilters.apply {
                add("armeabi-v7a")
                add("arm64-v8a")
            }
        }

        applicationVariants.all {
            outputs.all {
                if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                    this.outputFileName = "cloud-$versionName.apk"
                }
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        register("release") {
            enableV1Signing = true
            enableV2Signing = true
            System.getenv("CLOUD_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storeType = "PKCS12"
            storePassword = System.getenv("CLOUD_STORE_PASSWORD") ?: getLocalValue("STORE_PASSWORD")
            keyAlias = System.getenv("CLOUD_KEY_ALIAS") ?: "cloud"
            keyPassword = System.getenv("CLOUD_KEY_PASSWORD") ?: getLocalValue("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

fun getLocalValue(key: String): String {
    return getLocalValue(key, false)
}

fun getLocalValue(key: String, quot: Boolean): String {
    val properties = Properties()
    val localProperties = project.rootProject.file("local.properties")
    if (localProperties.exists()) {
        localProperties.inputStream().use { properties.load(it) }
    }
    var value = if (properties.containsKey(key)) {
        properties[key].toString()
    } else {
        ""
    }
    if (quot) {
        value = "\"" + value + "\""
    }
    return value
}

ksp {
    arg("moduleName", project.name)
    // crouter 默认 scheme
    arg("defaultScheme", "app")
    // crouter 默认 host
    arg("defaultHost", "music")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.media3.exoplayer)
    implementation("androidx.media3:media3-exoplayer-hls:${libs.versions.media3.get()}")
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.preference)
    implementation(libs.flexbox)
    implementation(libs.glance)

    ksp(libs.room.compiler)
    implementation(libs.room)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt)

    if (File("${project.projectDir}/google-services.json").exists()) {
        println("Enable gms in app dependencies")
        implementation(libs.crashlytics)
        implementation(libs.analytics)
    }

    implementation(libs.common)
    // implementation(project(":common"))
    ksp(libs.crouter.processor)
    implementation(libs.crouter.api)
    implementation(libs.lrcview)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.loggingInterceptor)
    implementation(libs.zbar)
    implementation(libs.blurry)
    implementation(libs.banner)
}
