plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

android {
    namespace = "me.lingci.lib.player.exo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 24

        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":lib-base"))
    api(project(":lib-player:dkplayer-java"))
    //api(project(":lib-player:exo-ext"))
    //api(libs.dkplayer.java)
    api(libs.bundles.media3.exo)
    api(libs.media3.inspector)
    api(libs.media3.effect)
    api(libs.media3.ffmpeg) {
        exclude(group = "androidx.media3")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "androidx.lifecycle")
    }

}
