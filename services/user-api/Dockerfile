# Multi-stage build for user-api
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

# Copy source code
COPY libs/common/src libs/common/src
COPY user-api/src user-api/src

# Build the application
RUN ./gradlew :user-api:bootJar --no-daemon

# Runtime stage.
# glibc, not -alpine: kept identical to control-api and agent-worker, which need it for
# netty-tcnative (its native TLS library is glibc-linked and faults on musl). user-api does
# not terminate TLS itself today, but one base image across the three services means a gRPC
# or TLS dependency added here later cannot reintroduce the same crash.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create non-root user (Debian/Ubuntu adduser, not the busybox flags Alpine used)
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy the built jar
COPY --from=builder /app/user-api/build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8088/actuator/health || exit 1

# Run the application with virtual threads enabled
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]
