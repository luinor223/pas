// Shared library (base entity, outbox, error model, header-based auth). Plain java-library, not a Boot app.

plugins {
    `java-library`
    // the wire-shape fixture every consumer test builds its records with, so a consumer
    // spec cannot drift from what OutboxRelay actually publishes
    `java-test-fixtures`
}

dependencies {
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.data.redis)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.json)
    api(libs.spring.kafka)
    api(libs.grpc.api)
    compileOnly(libs.springdoc.openapi)
    // @GlobalServerInterceptor for the shared correlation interceptor; provided at runtime by each service.
    compileOnly(libs.spring.grpc.core)

    testFixturesApi(libs.spring.kafka)

    testImplementation(libs.spring.boot.starter.test)
    // Hibernate picks its JSON FormatMapper by classpath detection and looks for Jackson 2, not
    // Jackson 3 — so OutboxEvent.payload (@JdbcTypeCode JSON) cannot be written without it. Every
    // service already has it transitively; this slim library does not.
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.postgresql)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.generic)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Same convention as the services: Docker-backed tests are opt-in, so the default
    // build stays runnable without a container runtime.
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
