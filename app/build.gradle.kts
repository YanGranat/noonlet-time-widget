import java.util.Locale
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingFile = File(System.getProperty("user.home"), ".android/noonlet-signing.properties")
val releaseKeys =
    Properties().apply {
        if (signingFile.exists()) signingFile.inputStream().use(::load)
    }

android {
    namespace = "app.noonlet"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.noonlet"
        minSdk = 26
        targetSdk = 36
        versionCode = 57
        versionName = "1.4.39"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("release") {
                storeFile = file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            vcsInfo.include = false
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1536m"
    val crashDirectory = layout.buildDirectory.dir("test-worker").get().asFile
    val temporaryDirectory = File(crashDirectory, "tmp")
    doFirst { temporaryDirectory.mkdirs() }
    systemProperty("java.io.tmpdir", temporaryDirectory.absolutePath)
    jvmArgs("-XX:ErrorFile=${File(crashDirectory, "hs_err_pid%p.log").absolutePath}")

    // Instrumented ICU constructors can crash the Windows JBR C2 compiler.
    if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) {
        jvmArgs("-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=256m")
    }
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
