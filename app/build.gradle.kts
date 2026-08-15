plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    jacoco
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
        debug {
            enableAndroidTestCoverage = true
        }
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

// Resolves just the mockito-core jar (no transitive deps) so it can be passed
// as a -javaagent path below. Mockito's inline mock maker otherwise self-attaches
// as an agent at runtime, which newer JDKs warn about and will eventually block.
val mockitoAgent: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
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
    mockitoAgent(libs.mockito.core)

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

tasks.withType<Test>().configureEach {
    // Deferred (not resolved at configuration time) via CommandLineArgumentProvider —
    // resolving the mockitoAgent configuration eagerly here breaks other tasks' configuration.
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${mockitoAgent.singleFile}")
    })
    extensions.configure<JacocoTaskExtension> {
        // Robolectric loads shadowed classes through its own SandboxClassLoader, which
        // JaCoCo's exec-data writer otherwise drops as "no location" classes, producing
        // a report that shows 0% coverage for every Robolectric-based test.
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        html.required.set(true)
        xml.required.set(false)
    }
    val fileFilter = listOf("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*")
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) { exclude(fileFilter) }
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    executionData.setFrom(fileTree(buildDir) { include("**/testDebugUnitTest.exec", "**/jacoco/testDebugUnitTest.exec") })
}

afterEvaluate {
    tasks.named("connectedDebugAndroidTest") {
        dependsOn("backupAppImages", "backupTriggerLog")
    }
}