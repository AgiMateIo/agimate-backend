# Multi-stage build for agent-worker
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .

# Copy every module's build file. Gradle refuses to configure an included project whose
# directory does not exist, so all of them must be present even though this image builds one.
COPY libs/common/build.gradle.kts libs/common/
COPY libs/agentworker-proto/build.gradle.kts libs/agentworker-proto/
COPY user-api/build.gradle.kts user-api/
COPY control-api/build.gradle.kts control-api/
COPY agent-worker/build.gradle.kts agent-worker/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code. agentworker-proto carries the .proto files the gRPC stubs are generated from.
COPY libs/common/src libs/common/src
COPY libs/agentworker-proto/src libs/agentworker-proto/src
COPY agent-worker/src agent-worker/src

# Build the application
RUN ./gradlew :agent-worker:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy the built jar
COPY --from=builder /app/agent-worker/build/libs/*.jar app.jar

# Headless queue consumer: no web server, so no port and no HTTP health check.

# Run the application with virtual threads enabled
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]
