plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.healthmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.healthmonitor"
        minSdk = 30
        targetSdk = 36
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
    }

    // Hiện chi tiết chỗ nào dùng API deprecated
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

dependencies {
    // ── AndroidX cơ bản ──────────────────────────────────
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // ── Fragment + ViewPager2 ─────────────────────────────
    implementation("androidx.fragment:fragment:1.6.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // ── Lifecycle (LiveData + ViewModel) ──────────────────
    implementation("androidx.lifecycle:lifecycle-livedata:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.7.0")

    // ── Retrofit + OkHttp (HTTP API) ──────────────────────
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")

    // ── Room Database (SQLite local) ──────────────────────
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    // ── MQTT Paho ─────────────────────────────────────────
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // ── WorkManager (background sync) ─────────────────────
    implementation("androidx.work:work-runtime:2.9.0")

    // ── MPAndroidChart (biểu đồ) ──────────────────────────
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ── CardView ──────────────────────────────────────────
    implementation("androidx.cardview:cardview:1.0.0")

    // ── CoordinatorLayout ─────────────────────────────────
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // ── Test ──────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}