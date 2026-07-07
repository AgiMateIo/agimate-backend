plugins {
    id("java-library")
    id("com.google.protobuf") version "0.9.4"
}

group = "ru.agimate"
version = findProperty("buildVersion") ?: "0.0.1"

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
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    api("com.google.protobuf:protobuf-java:$protobufVersion")

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
