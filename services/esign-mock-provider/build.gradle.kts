plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jib)
}

jib {
    from {
        image = "gcr.io/distroless/java25-debian13:nonroot"
    }
    to {
        image = "ghcr.io/luinor223/pas-esign-mock-provider"
    }
    container {
        ports = listOf("9001")
        jvmFlags = listOf("-XX:MaxRAMPercentage=65")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.json)

    testImplementation(libs.spring.boot.starter.test)
}
