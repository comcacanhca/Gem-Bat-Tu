plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

allprojects {
    tasks.register("testAll") {
        group = "verification"
        description = "Chạy tất cả Android unit tests của module :app"
        dependsOn(":app:testDebugUnitTest")
    }

    tasks.register("testAndroidInstrumented") {
        group = "verification"
        description = "Chạy Android instrumented tests (cần device/emulator)"
        dependsOn(":app:connectedDebugAndroidTest")
    }

    tasks.register<Exec>("testGateway") {
        group = "verification"
        description = "Chạy Cloudflare Gateway Vitest tests"
        workingDir = file("cloudflare-gateway")
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            commandLine("cmd", "/c", "npm test")
        } else {
            commandLine("sh", "-c", "npm test")
        }
    }

    tasks.register<Exec>("testGatewayCoverage") {
        group = "verification"
        description = "Chạy Cloudflare Gateway Vitest tests với coverage"
        workingDir = file("cloudflare-gateway")
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            commandLine("cmd", "/c", "npm run test:coverage")
        } else {
            commandLine("sh", "-c", "npm run test:coverage")
        }
    }
}
