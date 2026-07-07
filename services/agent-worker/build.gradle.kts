plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val grpcVersion = "1.68.1"
val springAiVersion = "2.0.0"

group = "ru.agimate.agentworker"
version = findProperty("buildVersion") ?: "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

dependencies {
    // Generated agent-worker gRPC/protobuf stubs (shared with control-api).
    implementation(project(":libs:agentworker-proto"))

    // Spring Boot — headless (non-web) DBOS queue consumer.
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring AI OpenAI model module (no starter: the ChatModel is built per-call
    // from dynamic per-agent credentials, so we avoid the autoconfigured bean).
    implementation("org.springframework.ai:spring-ai-openai")

    // DBOS durable workflows / queues (same lib the control-api producer uses).
    implementation("dev.dbos:transact:0.9.0")

    // Bounded cache for per-credentials chat models (reuses the underlying HTTP clients).
    implementation("com.github.ben-manes.caffeine:caffeine")

    // gRPC client transport (stubs come transitively from :libs:agentworker-proto).
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")

    // Postgres driver for the DBOS system database.
    runtimeOnly("org.postgresql:postgresql")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
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
