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
    }
}

rootProject.name = "shield"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:common")
include(":core:model")
include(":core:design-system")
include(":core:database")
include(":core:security")
include(":core:testing")
include(":protection:domain-engine")
include(":protection:dns")
include(":protection:vpn")
include(":protection:health")
include(":protection:boot")
include(":protection:oem")
include(":data:blocklist")
include(":data:preferences")
include(":data:repository")
include(":feature:onboarding")
include(":feature:setup")
include(":feature:dashboard")
include(":feature:reports")
include(":feature:diagnostics")
include(":feature:support")
include(":feature:settings")