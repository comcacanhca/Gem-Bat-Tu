import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
}

secrets {
    // Specify properties file containing secrets (defaults to local.properties)
    propertiesFileName = "local.properties"

    // Default secret values checked into VCS for fallback
    defaultPropertiesFileName = "secrets.defaults.properties"

    // Keys to ignore during BuildConfig generation
    ignoreList.add("sdk.*")
}

android {
    namespace = "com.batubot.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.batubot.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 20
        versionName = "2.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // sherpa-onnx native lib: chỉ build 2 ABI phổ thông (giảm ~50% native size;
            // AAB tự lọc khi publish Play)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        val secretsFile = rootProject.file("secrets.defaults.properties")
        val secretsProps = Properties()
        if (secretsFile.exists()) {
            secretsProps.load(secretsFile.inputStream())
        }
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            secretsProps.load(localPropsFile.inputStream())
        }
        val appGatewayToken = secretsProps.getProperty("APP_GATEWAY_TOKEN", "sk-live-1234567890abcdef")
        buildConfigField("String", "APP_GATEWAY_TOKEN", "\"$appGatewayToken\"")

        val clineApiKey = secretsProps.getProperty("CLINE_API_KEY", "")
        buildConfigField("String", "CLINE_API_KEY", "\"$clineApiKey\"")

        val ollamaApiKey = secretsProps.getProperty("OLLAMA_API_KEY", "")
        buildConfigField("String", "OLLAMA_API_KEY", "\"$ollamaApiKey\"")

        val nvidiaApiKey = secretsProps.getProperty("NVIDIA_API_KEY", "")
        buildConfigField("String", "NVIDIA_API_KEY", "\"$nvidiaApiKey\"")

        val d1ApiKey = secretsProps.getProperty("D1_API_KEY", "")
        buildConfigField("String", "D1_API_KEY", "\"$d1ApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/scripts/java")
        }
        getByName("test") {
            assets.srcDirs("src/main/assets", "$projectDir/schemas")
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // .db giữ nguyên (không nén) để đọc trực tiếp từ assets khi cần
    androidResources {
        noCompress.add(".db")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED"
    )
}

dependencies {
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-analytics")

    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jsoup:jsoup:1.19.1")
    implementation("com.caverock:androidsvg-aar:1.4")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    val hiltVersion = "2.57.2"
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

afterEvaluate {
    val debugUnitTestClasspath = files(
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("intermediates/classes/debug"),
        layout.buildDirectory.dir("classes/kotlin/main"),
        configurations.findByName("debugUnitTestRuntimeClasspath"),
        configurations.findByName("debugRuntimeClasspath"),
        configurations.findByName("testRuntimeClasspath"),
        android.bootClasspath
    )

    android.sourceSets.maybeCreate("unitTest")

    android.sourceSets.forEach { sourceSet ->
        (sourceSet as? groovy.lang.GroovyObject)?.metaClass?.let { mc ->
            try {
                mc.setProperty(sourceSet, "runtimeClasspath", debugUnitTestClasspath)
            } catch (_: Exception) {}
        }
    }
}

tasks.register("updateModelsJson", JavaExec::class.java) {
    group = "agent"
    description = "Checks model health and updates models.json"
    dependsOn("compileDebugKotlin")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("tmp/kotlin-classes/scripts"),
        configurations.findByName("debugRuntimeClasspath") ?: configurations.findByName("testRuntimeClasspath")
    )
    mainClass.set("com.batubot.app.agent.CheckModelsKt")
    environment("CLINE_API_KEY", System.getenv("CLINE_API_KEY") ?: "")
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: System.getenv("GEMINI_API") ?: "")
    environment("OPENROUTER_API_KEY", System.getenv("OPENROUTER_API_KEY") ?: "")
    environment("NVIDIA_API_KEY", System.getenv("NVIDIA_API_KEY") ?: "")
}

tasks.register("liveWsProbe", JavaExec::class.java) {
    group = "agent"
    description = "Probe Gemini Live WebSocket with OkHttp from JVM (transport isolation test)"
    dependsOn("compileDebugKotlin")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("tmp/kotlin-classes/scripts"),
        configurations.findByName("debugRuntimeClasspath") ?: configurations.findByName("testRuntimeClasspath")
    )
    mainClass.set("com.batubot.app.agent.LiveWsProbeKt")
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
}

tasks.register("livePcTalkProbe", JavaExec::class.java) {
    group = "agent"
    description = "PC voice chat with Gemini Live (PC mic + speaker via javax.sound)"
    dependsOn("compileDebugKotlin")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("tmp/kotlin-classes/scripts"),
        configurations.findByName("debugRuntimeClasspath") ?: configurations.findByName("testRuntimeClasspath")
    )
    mainClass.set("com.batubot.app.agent.LivePcTalkProbeKt")
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
