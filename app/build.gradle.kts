plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-NOTICE.md", "META-INF/NOTICE.md")
        }
    }
    
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
        
    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 16
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // NDK configuration for Monerujo native libraries
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    // Updated packaging configuration for Monerujo
    packaging {
        // For .so files (native libraries)
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libjsc.so"
            pickFirsts += "**/libmonerujo.so"  // Add Monerujo library
        }
        // For other resources
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
    
    // Updated Kotlin compiler options
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    buildFeatures {
        compose = true
    }
    
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // --- Lombok (compile-time only, not packaged in APK) ---
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // Optional: For annotation processing in test sources
    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)
    
    // Monerujo dependencies
    implementation("org.json:json:20231013")
    
    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Security for wallet encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")    

    // WebSocket
    implementation(libs.okhttp)

    // Google Play Services Location
    implementation(libs.gms.location)

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

