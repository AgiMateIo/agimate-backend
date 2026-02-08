plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "ru.agimate.connectorsapi"
version = findProperty("buildVersion") ?: "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Internal Project Dependencies
    implementation(project(":libs:common"))

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")


    // HTTP Client for external API calls
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:logging-interceptor")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")

    // SpringDoc for OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    // JWT Dependencies
    implementation("io.jsonwebtoken:jjwt-api")
    implementation("io.jsonwebtoken:jjwt-impl")
    implementation("io.jsonwebtoken:jjwt-jackson")

    // Spring gRPC Client Support
    implementation("org.springframework.grpc:spring-grpc-spring-boot-starter")

    // Lombok for code generation
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing Dependencies
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.awaitility:awaitility:4.2.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

springBoot {
    buildInfo()
}

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = true
}

// OpenAPI specification generation tasks

tasks.register<Test>("generateOpenApiTest") {
    group = "documentation"
    description = "Generate OpenAPI specification by running test"

    useJUnitPlatform {
        includeTags("openapi-generation")
    }

    // Ensure output directory exists
    doFirst {
        project.file("build/generated/openapi").mkdirs()
    }

    outputs.upToDateWhen { false } // Always run
}

tasks.register<Copy>("copyOpenApiSpec") {
    group = "documentation"
    description = "Copy generated OpenAPI spec to resources directory"

    dependsOn("generateOpenApiTest")

    from("build/generated/openapi/openapi.json")
    into("src/main/resources/static")

    doLast {
        println("✓ Copied OpenAPI spec to src/main/resources/static/openapi.json")
        println("  Remember to commit this file to git!")
    }
}

tasks.register("generateOpenApi") {
    group = "documentation"
    description = "Generate OpenAPI specification and copy to resources"

    dependsOn("generateOpenApiTest", "copyOpenApiSpec")

    doLast {
        println("")
        println("========================================")
        println("OpenAPI specification generated successfully!")
        println("File: src/main/resources/static/openapi.json")
        println("========================================")
    }
}