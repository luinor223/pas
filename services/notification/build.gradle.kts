plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jib)
}

jib {
    from {
        image = "gcr.io/distroless/java25-debian13:nonroot"
    }
    to {
        image = "ghcr.io/luinor223/pas-notification"
    }
    container {
        ports = listOf("8008")
        jvmFlags = listOf("-XX:MaxRAMPercentage=65")
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.grpc:spring-grpc-dependencies:${libs.versions.springGrpc.get()}")
    }
}

dependencies {
    implementation(project(":libs:common"))
    implementation(project(":proto"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.springdoc.openapi)
    implementation(libs.spring.grpc.starter)

    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.kafka)

    testImplementation(libs.spring.boot.starter.test)
    // @WithMockUser: Phase A pins the permission gates, which nothing else exercises
    testImplementation("org.springframework.security:spring-security-test")
    // records shaped by the real OutboxRelay, so this consumer's spec cannot drift from what
    // the seven producers actually publish
    testImplementation(testFixtures(project(":libs:common")))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.generic)
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.awaitility:awaitility:4.3.0")
}

tasks.test {
    jvmArgs("-Duser.timezone=UTC")
    useJUnitPlatform {
        val include = project.findProperty("includeIntegration") != null
                || System.getProperty("includeIntegration") != null
        if (include) {
            includeTags("integration")
        } else {
            excludeTags("integration")
        }
    }
}
