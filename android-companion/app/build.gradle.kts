plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("jacoco")
  id("org.jlleitschuh.gradle.ktlint")
}

android {
  namespace = "com.cssupport.companion"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.cssupport.companion"
    minSdk = 28  // Raised to 28 (Android 9) for EncryptedSharedPreferences support.
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "CHATGPT_OAUTH_CLIENT_ID", "\"${project.findProperty("CHATGPT_OAUTH_CLIENT_ID") ?: "app_EMoamEEZ73f0CkXaXp7hrann"}\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      it.maxHeapSize = "2048m"
      it.jvmArgs("-XX:+UseG1GC")
    }
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    buildConfig = true
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

  // Encrypted SharedPreferences for secure credential storage.
  implementation("androidx.security:security-crypto:1.1.0-alpha06")

  // Chrome Custom Tabs for OAuth browser flows
  implementation("androidx.browser:browser:1.8.0")

  // Layout components used across screens
  implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")

  // Unit testing
  testImplementation("junit:junit:4.13.2")
  testImplementation("io.mockk:mockk:1.13.16")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
  testImplementation("org.json:json:20231013")
  testImplementation("org.robolectric:robolectric:4.14.1")
}

// ── JaCoCo coverage reporting ───────────────────────────────────────────

tasks.register<JacocoReport>("jacocoTestReport") {
  dependsOn("testDebugUnitTest")

  reports {
    xml.required.set(true)
    html.required.set(true)
  }

  val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
    exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.class")
  }
  val mainSrc = "${project.projectDir}/src/main/java"

  sourceDirectories.setFrom(files(mainSrc))
  classDirectories.setFrom(files(debugTree))
  executionData.setFrom(fileTree(layout.buildDirectory) {
    include("jacoco/testDebugUnitTest.exec")
  })
}

// ── JaCoCo coverage verification (minimum thresholds) ────────────────────

tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
  dependsOn("testDebugUnitTest")

  val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
    exclude(
      "**/R.class", "**/R\$*.class", "**/BuildConfig.class",
      // Exclude Android components that are difficult to unit-test.
      "**/*Activity*", "**/*Service*", "**/*Application*",
    )
  }

  classDirectories.setFrom(files(debugTree))
  executionData.setFrom(fileTree(layout.buildDirectory) {
    include("jacoco/testDebugUnitTest.exec")
  })

  violationRules {
    rule {
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        minimum = "0.35".toBigDecimal()
      }
    }
    rule {
      limit {
        counter = "BRANCH"
        value = "COVEREDRATIO"
        minimum = "0.25".toBigDecimal()
      }
    }
  }
}
