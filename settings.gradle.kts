pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "Tesseract4Android"
                    url = uri("https://jitpack.io")
                }
            }
            filter {
                includeVersion("cz.adaptech.tesseract4android", "tesseract4android", "4.9.0")
            }
        }
    }
}

rootProject.name = "PhotoLibrary"
include(":app")
