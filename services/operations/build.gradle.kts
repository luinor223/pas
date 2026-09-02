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

    implementation(libs.spring.boot.kafka)

    // identity signs JWTs; verification lives in common
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.generic)
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.awaitility:awaitility:4.3.0")
}

// Integration tests (Testcontainers) need Docker; excluded from the default build.
// Enable with: ./gradlew test -PincludeIntegration  or  -DincludeIntegration=true
tasks.test {
    // PG TimeZone Asia/Saigon invalid on PG16 image -> force UTC for H2/PSQL
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
