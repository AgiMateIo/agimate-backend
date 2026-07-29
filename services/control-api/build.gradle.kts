plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "ru.agimate.controlapi"
version = findProperty("buildVersion") ?: "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // Astronomy Engine (astro-коннектор) публикуется только на JitPack, в Maven Central его нет
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Internal Project Dependencies
    implementation(project(":libs:common"))
    // Generated agent-worker gRPC/protobuf stubs (shared with agent-worker).
    // Brings grpc-protobuf, grpc-stub and protobuf-java transitively (api).
    implementation(project(":libs:agentworker-proto"))

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // JWT dependencies
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")

    // SpringDoc for OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    // Caffeine cache for ABAC policy evaluation
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("com.squareup.okhttp3:okhttp")

    // Астрономические расчёты astro-коннектора: MIT, self-contained, ±1 угл. минута
    implementation("io.github.cosinekitty:astronomy")

    // Графики sheets-коннектора: PNG через Java2D (Spring Boot ставит java.awt.headless=true)
    implementation("org.knowm.xchart:xchart") {
        // Only BitmapEncoder is used, and it renders through ImageIO alone. The other two
        // encoders ship their own backends: SVG/EPS pulls in LGPL VectorGraphics2D, PDF pulls
        // in PDFBox — and pdfbox-io 3.0.1 declares JUnit as a compile dependency, which is how
        // the test framework ends up inside the production jar. Roughly 5 MB of dead weight.
        exclude(group = "de.erichseifert.vectorgraphics2d")
        exclude(group = "de.rototor.pdfbox")
        exclude(group = "org.apache.pdfbox")
    }

    // Импорт/экспорт xlsx в sheets-коннекторе
    implementation("org.dhatim:fastexcel")
    implementation("org.dhatim:fastexcel-reader")

    // S3-совместимое хранилище файлового слоя коннекторов (docs/connectors/files.md)
    implementation(platform("software.amazon.awssdk:bom:2.49.1"))
    implementation("software.amazon.awssdk:s3")

    implementation("dev.dbos:transact:1.0.0")

    implementation("org.opensolutionlab.httpclients:javacent:2.0.0")

    // gRPC server runtime for the worker protocol (stubs come from :libs:agentworker-proto,
    // versions from the root constraints block).
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-services")

    // Lombok for code generation
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing Dependencies
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.grpc:grpc-inprocess")
    testImplementation("io.grpc:grpc-testing")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    listOf("generate.worker.authkey").forEach { key ->
        System.getProperty(key)?.let {
            systemProperty(key, it)
            // Generators print the key to stdout; Gradle hides it unless asked, and would
            // skip the task as UP-TO-DATE on a repeat run with unchanged sources.
            testLogging.showStandardStreams = true
            outputs.upToDateWhen { false }
        }
    }
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