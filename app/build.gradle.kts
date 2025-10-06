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
    
    //Guava
    implementation(libs.guava)
    
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

// Add this at the END of your app/build.gradle.kts file
// (after the dependencies block)

tasks.register<JavaExec>("runMoneroFetcher") {
    group = "execution"
    description = "Run MoneroTransactionFetcher main method"
    
    // Set the main class to run
    mainClass.set("com.bitchat.android.monero.utils.MoneroTransactionFetcher")
    
    // Set classpath - includes compiled classes and runtime dependencies
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
        configurations.getByName("debugRuntimeClasspath")
    )
    
    // Optional: Pass arguments (transaction ID, etc.)
    if (project.hasProperty("txid")) {
        args(project.property("txid"))
    }
    
    // Set working directory (optional)
    workingDir = projectDir
    
    // Ensure classes are compiled before running
    dependsOn("compileDebugJavaWithJavac")
    
    // Set standard input/output (to see println output)
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}

// Optional: Create additional tasks for specific use cases

tasks.register<JavaExec>("searchTransaction") {
    group = "execution"
    description = "Search for a specific transaction by ID"
    
    mainClass.set("com.bitchat.android.monero.utils.MoneroTransactionFetcher")
    
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
        configurations.getByName("debugRuntimeClasspath")
    )
    
    // Get transaction ID from command line
    if (project.hasProperty("txid")) {
        args(project.property("txid"))
    } else {
        println("Usage: ./gradlew searchTransaction -Ptxid=YOUR_TRANSACTION_ID")
    }
    
    dependsOn("compileDebugJavaWithJavac")
    
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}

// Optional: Task with predefined test transaction ID
tasks.register<JavaExec>("testMoneroFetcher") {
    group = "execution"
    description = "Test MoneroTransactionFetcher with default test values"
    
    mainClass.set("com.bitchat.android.monero.utils.MoneroTransactionFetcher")
    
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
        configurations.getByName("debugRuntimeClasspath")
    )
    
    // Set test/default values
    args("test_txid_here")
    
    dependsOn("compileDebugJavaWithJavac")
    
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
}

