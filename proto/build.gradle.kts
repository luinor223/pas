import com.google.protobuf.gradle.id

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    api("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
    api("io.grpc:grpc-stub:${libs.versions.grpcJava.get()}")
    api("io.grpc:grpc-protobuf:${libs.versions.grpcJava.get()}")
    api("jakarta.annotation:jakarta.annotation-api:3.0.0")
}

val protobufVersion = libs.versions.protobuf.get()
val grpcJavaVersion = libs.versions.grpcJava.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcJavaVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}
