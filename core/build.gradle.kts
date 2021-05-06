plugins {
    id("com.android.library")
}

android {
    compileSdkVersion(28)

    defaultConfig {
        minSdkVersion(28)
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.setSrcDirs(listOf("res"))
        }
    }

    libraryVariants.all {
        generateBuildConfigProvider?.configure {
            enabled = false
        }
    }
}
