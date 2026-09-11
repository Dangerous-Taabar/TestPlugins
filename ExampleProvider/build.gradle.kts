dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jspecify:jspecify:1.0.0")
}

version = 1

cloudstream {
    description = "Desi TV Serialz - Hindi TV Shows Extension"
    authors = listOf("Taabar")

    status = 1

    tvTypes = listOf("TvSeries", "Movie")

    requiresResources = true
    language = "hi"

    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjspecify-annotations=ignore")
    }
}
