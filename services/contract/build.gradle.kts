plugins {
    alias(libs.plugins.spring.boot)
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
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.generic)
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.awaitility:awaitility:4.3.0")
}

tasks.test {
    jvmArgs("-Duser.timezone=UTC")
    systemProperty("PAGINATION_CURSOR_SECRET", "contract-test-pagination-cursor-secret-32chars")
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
