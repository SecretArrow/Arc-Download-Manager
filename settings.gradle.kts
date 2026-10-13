pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jlibtorrent (BitTorrent engine) is distributed on FrostWire's Maven repository.
        maven {
            url = uri("https://dl.frostwire.com/maven")
            content {
                includeGroup("com.frostwire")
            }
        }
    }
}

rootProject.name = "Arc Download Manager"
include(":app")
