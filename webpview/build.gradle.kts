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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
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
                // 正式发布默认使用当前稳定版本；本地联调可通过 -PpublishVersion 覆盖。
                //   ./gradlew :webpview:publishReleasePublicationToMavenLocal -PpublishVersion=1.0.5-local
                version = (findProperty("publishVersion") as String?) ?: "1.0.5"
            }
        }
    }
}
