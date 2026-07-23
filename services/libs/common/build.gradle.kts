plugins {
    id("java-library")
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

group = "ru.agimate"
version = findProperty("buildVersion") ?: "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    compileOnly("org.springframework.boot:spring-boot-starter")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.springframework.boot:spring-boot-starter-data-jpa")
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    compileOnly("io.jsonwebtoken:jjwt-api:0.12.6")
    compileOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    compileOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    compileOnly("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    compileOnly("io.micrometer:micrometer-core")
    compileOnly("com.squareup.okhttp3:okhttp")

    api("com.github.ben-manes.caffeine:caffeine")

    implementation("org.springframework.data:spring-data-commons")

    implementation("org.apache.commons:commons-lang3")
    implementation("commons-io:commons-io")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    api("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.7")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.jar {
    enabled = true
}

