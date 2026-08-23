plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "ru.agimate.userapi"
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
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Confirmation and password-reset mail; the transport is plain SMTP so that an installation
    // can point it at its own mailbox instead of registering with a sending service.
    implementation("org.springframework.boot:spring-boot-starter-mail")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")

    // Testing dependencies
    testImplementation("com.h2database:h2")

    // SpringDoc for OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    // Access tokens for the FCM push channel (service account -> OAuth2), version in the root build
    implementation("com.google.auth:google-auth-library-oauth2-http")

    // JWT Dependencies
    implementation("io.jsonwebtoken:jjwt-api")
    implementation("io.jsonwebtoken:jjwt-impl")
    implementation("io.jsonwebtoken:jjwt-jackson")

    // Lombok for code generation
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Testing Dependencies
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Manual tools disabled by default: they are enabled by a system property, and a property given
    // to Gradle does not reach the test JVM on its own. mail.smoke.* carry the relay to send through.
    val manualProperties = listOf("generate.internal.authkey", "mail.smoke", "mail.smoke.host",
        "mail.smoke.port", "mail.smoke.ssl", "mail.smoke.username", "mail.smoke.password",
        "mail.smoke.from", "mail.smoke.to")
    manualProperties.forEach { key ->
        System.getProperty(key)?.let {
            systemProperty(key, it)
            // They print to stdout; Gradle hides it unless asked, and would skip the task as
            // UP-TO-DATE on a repeat run with unchanged sources.
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