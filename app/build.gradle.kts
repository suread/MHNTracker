plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.readablesoftware.mhntracker"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.readablesoftware.mhntracker"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // Required to include test resources (video files)
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "4g" // OOM loading frame images
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.junit.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // OpenCV - image processing
    implementation(libs.opencv)

    // ML Kit - OCR
    implementation(libs.text.recognition)

    // Room - database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockito.kotlin)

}

// Shared by both backup tasks below so images and the trigger log from the
// same test run land in the same image-backups/run-<timestamp>/ folder.
val backupOutputDir = file("$rootDir/image-backups").apply { mkdirs() }
val backupTimestamp = System.currentTimeMillis().toString()
val backupDestDir = file("$backupOutputDir/run-$backupTimestamp")

// Pulls image files off the device before instrumented tests wipe the app.
tasks.register<Exec>("backupAppImages") {
    commandLine(
        "adb", "pull",
        "/sdcard/Android/data/com.readablesoftware.mhntracker/files/sessions",
        backupDestDir.absolutePath
    )
    isIgnoreExitValue = true // don't fail the build if the folder doesn't exist yet (first run)
}

// Pulls the Hunt Report trigger-classification diagnostic log (see
// FightHandler.logTriggerClassification) off the device for the same reason —
// instrumented tests wipe the app, and this data is being collected to decide
// whether the "Hunt Report" title check can eventually be dropped.
tasks.register<Exec>("backupTriggerLog") {
    backupDestDir.mkdirs()
    commandLine(
        "adb", "pull",
        "/sdcard/Android/data/com.readablesoftware.mhntracker/files/hunt_report_trigger_log.log",
        backupDestDir.absolutePath
    )
    isIgnoreExitValue = true // don't fail the build if the log doesn't exist yet (first run)
}

afterEvaluate {
    tasks.named("connectedDebugAndroidTest") {
        dependsOn("backupAppImages", "backupTriggerLog")
    }
}