plugins {
    id("java-library")
    id("com.google.protobuf") version "0.10.0"
}

group = "ru.agimate"
version = findProperty("buildVersion") ?: "0.0.1"

// Codegen tool versions; runtime artifact versions come from the root constraints block —
// keep the two in sync when bumping.
val grpcVersion = "1.68.1"
val protobufVersion = "3.25.5"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Exposed via `api` so every consumer (control-api server, agent-worker client)
    // compiles against the generated stubs and the gRPC/protobuf runtime types.
    // Versions come from the root constraints block.
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    api("com.google.protobuf:protobuf-java")

    // Needed only to compile the generated gRPC stubs (@javax.annotation.Generated).
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
