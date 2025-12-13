pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = uri(providers.gradleProperty("repo.proxy.url")) }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "rich-filter"
include(":rich-filter")
include(":exposed-op-builder")
