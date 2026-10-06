import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gemmatranslator.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gemmatranslator.offline"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // Local sideload builds use the standard debug key. Supply a separate
            // release signing configuration before publishing to an app store.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-1.13.8.aar")
val downloadSherpa by tasks.registering {
    group = "build setup"
    description = "Download the pinned official offline speech runtime and verify its SHA-256."
    outputs.file(sherpaAar)
    doLast {
        val destination = sherpaAar.asFile
        val expected = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!destination.exists() || sha256(destination) != expected) {
            destination.parentFile.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".part")
            val url = URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL()
            val connection = url.openConnection().apply {
                connectTimeout = 30000
                readTimeout = 120000
            }
            connection.getInputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256(temporary) == expected) { "Sherpa ONNX checksum mismatch; speech runtime was not installed." }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadSherpa) }

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
    implementation(files(sherpaAar).builtBy(downloadSherpa))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
}
