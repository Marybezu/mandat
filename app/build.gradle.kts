import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "ua.mandat"
    compileSdk = 36

    defaultConfig {
        applicationId = "ua.mandat"
        minSdk = 31
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Left unsigned here; signed later in a separate step that runs no build code
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Screen checks run on the computer (Robolectric) and need the app's resources
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Unlock with the phone's own fingerprint or PIN
    implementation("androidx.biometric:biometric:1.1.0")

    // Encrypted database: Room on top of SQLCipher
    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    implementation("androidx.sqlite:sqlite:2.5.2")

    // Argon2id for backup passwords (Bouncy Castle, a widely audited crypto library)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    // Password-protected export: standard ZIP with AES-256, opened by 7-Zip and similar apps
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    testImplementation("junit:junit:4.13.2")

    // Screen checks only (never in the app): draw each screen with neutral test data,
    // so a crash is caught before a release and the look can be reviewed
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
