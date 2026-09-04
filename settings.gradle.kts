pluginManagement {
    repositories {
        // Prefer Maven Central so Docker builds do not depend on the Plugin
        // Portal's redirect to plugins-artifacts.gradle.org.
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "pas"

include("libs:common")
include("proto")

include("services:identity")
include("services:workflow")
include("services:operations")
include("services:contract")
include("services:notification")
include("services:audit")
include("services:pricing")
include("services:billing")
include("services:esign")
include("services:esign-mock-provider")

// services:identity -> identity-service, libs:common -> common
rootProject.children.forEach { group ->
    group.children.forEach { module ->
        if (group.name == "services") module.name = "${module.name}-service"
    }
}
