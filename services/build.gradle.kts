plugins {
    java
    id("org.springframework.boot") version "4.1.0" apply false
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

                // gRPC/protobuf — one version for agentworker-proto, control-api and agent-worker
                // (gRPC artifacts must move in lockstep). Codegen tool artifacts (protoc,
                // protoc-gen-grpc-java) live in libs/agentworker-proto — bump them together.
                implementation("io.grpc:grpc-netty-shaded:1.68.1")
                implementation("io.grpc:grpc-protobuf:1.68.1")
                implementation("io.grpc:grpc-stub:1.68.1")
                implementation("io.grpc:grpc-services:1.68.1")
                implementation("io.grpc:grpc-inprocess:1.68.1")
                implementation("io.grpc:grpc-testing:1.68.1")
                implementation("com.google.protobuf:protobuf-java:3.25.5")

                implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
                implementation("org.springframework.boot:spring-boot-dependencies")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-aop")
                implementation("org.springframework.boot:spring-boot-starter-validation")
                implementation("org.springframework.boot:spring-boot-starter-actuator")

                implementation("org.springframework.boot:spring-boot-configuration-processor")
                annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

                // Версии ниже управляет Spring Boot BOM (spring-boot-dependencies) — здесь пиним
                // только то, чего в BOM нет. commons-lang3/commons-codec, lombok, spring-data-commons,
                // micrometer, jackson, postgresql, liquibase намеренно НЕ пиним: BOM перекрывает.
                // commons-io в BOM отсутствует — пин наш (owned).
                implementation("commons-io:commons-io:2.22.0")
                implementation("org.jetbrains:annotations:26.1.0")

                // FCM through RuStore's universal API (user-api): the whole of what is needed from
                // Google is an access token minted from a service account — firebase-admin sends
                // natively, and a natively sent message the universal SDK on the device discards
                implementation("com.google.auth:google-auth-library-oauth2-http:1.50.0")

                implementation("com.squareup.okhttp3:okhttp:5.3.2")
                implementation("com.squareup.okhttp3:okhttp-brotli:5.3.2")
                implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")

                // Astronomy Engine (astro-коннектор control-api) — с JitPack
                implementation("io.github.cosinekitty:astronomy:2.1.19")

                // Рендер PNG-графиков sheets-коннектора: Apache 2.0, поверх Java2D. Транзитивы
                // есть — бэкенды SVG/EPS и PDF; они отрезаны в control-api, см. там же почему
                implementation("org.knowm.xchart:xchart:3.8.8")

                // Импорт/экспорт xlsx в sheets-коннекторе: Apache 2.0, стриминговый, без xmlbeans
                // (Apache POI тянет десятки мегабайт ради формата, нужного только на краях потока)
                implementation("org.dhatim:fastexcel:0.19.0")
                implementation("org.dhatim:fastexcel-reader:0.19.0")
            }
        }
    }
}