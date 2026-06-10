import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "io.webpkit.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 28 // ImageDecoder / AnimatedImageDrawable require API 28

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-std=c++17"
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-common:2.6.2")
    implementation("androidx.annotation:annotation:1.6.0")
}

publishing {
    repositories {
        // Publishes into a plain folder that we serve as a Maven repo via GitHub raw
        // (the `mvn-repo` branch). Override with -PrepoDir=/path if needed.
        maven {
            name = "fileRepo"
            val repoDir = (findProperty("repoDir") as String?)
                ?: rootProject.layout.projectDirectory.dir("mvn-repo").asFile.absolutePath
            url = uri(repoDir)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.webpkit"
                artifactId = "player"
                // 本地联调可覆盖版本号发布到 mavenLocal，正式发布仍用默认值：
                //   ./gradlew :webpview:publishReleasePublicationToMavenLocal -PpublishVersion=1.0.2-local
                version = (findProperty("publishVersion") as String?) ?: "1.0.3"
            }
        }
    }
}
