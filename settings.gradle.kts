pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven {
            url = uri("https://maven.pkg.github.com/muedsa/snapshot")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GPR_USER"))
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GPR_KEY"))
            }
        }
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.5.2")
    }
}

rootProject.name = "open-snapshot"
