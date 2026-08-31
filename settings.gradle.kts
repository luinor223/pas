rootProject.name = "pas"

include("libs:common")
include("proto")

include("services:identity")
include("services:workflow")
include("services:contract")
include("services:notification")
include("services:audit")

// services:identity -> identity-service, libs:common -> common
rootProject.children.forEach { group ->
    group.children.forEach { module ->
        if (group.name == "services") module.name = "${module.name}-service"
    }
}
