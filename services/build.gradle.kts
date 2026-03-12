plugins {
    java
    id("org.springframework.boot") version "4.0.0" apply false
    id("org.asciidoctor.jvm.convert") version "4.0.4" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    pluginManager.withPlugin("java") {
        dependencies {
            constraints {
                implementation("net.logstash.logback:logstash-logback-encoder:7.4")
                implementation("io.jsonwebtoken:jjwt-api:0.12.6")
                implementation("io.jsonwebtoken:jjwt-impl:0.12.6")
                implementation("io.jsonwebtoken:jjwt-jackson:0.12.6")

                implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
                implementation("org.springframework.boot:spring-boot-dependencies")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-aop")
                implementation("org.springframework.boot:spring-boot-starter-validation")
                implementation("org.springframework.boot:spring-boot-starter-actuator")

                implementation("org.springframework.boot:spring-boot-configuration-processor")
                annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

                // Apache commons
                implementation("org.apache.commons:commons-lang3:3.17.0")
                implementation("commons-io:commons-io:2.17.0")
                implementation("commons-codec:commons-codec:1.18.0")

                implementation("org.projectlombok:lombok:1.18.36")
                annotationProcessor("org.projectlombok:lombok:1.18.36")

                implementation("org.springframework.data:spring-data-commons:3.3.5")
                implementation("io.micrometer:micrometer-core:1.15.1")
                implementation("org.jetbrains:annotations:26.0.0")

                implementation("com.squareup.okhttp3:okhttp:5.3.2")
                implementation("com.squareup.okhttp3:okhttp-brotli:5.3.2")
                implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")

                implementation("dev.langchain4j:langchain4j-core:1.1.0")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
                implementation("org.postgresql:postgresql:42.7.5")
                implementation("org.liquibase:liquibase-core:4.29.2")
            }
        }
    }
}