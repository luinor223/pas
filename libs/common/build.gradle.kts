// Shared library (base entity, outbox, error model, header-based auth). Plain java-library, not a Boot app.

plugins {
    `java-library`
}

dependencies {
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.data.redis)
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.json)

    testImplementation(libs.spring.boot.starter.test)
}
