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

# Runtime stage.
# glibc, not -alpine: grpc-netty-shaded bundles netty-tcnative, whose native library is
# built against glibc. On musl it loads and then dies in relocation —
#   Error relocating ...tcnative_linux_x86_64.so: __isnan: symbol not found
# which surfaces as a JVM SIGSEGV inside netty_internal_tcnative_SSLContext_JNI_OnLoad the
# moment a TLS context is created. Invisible locally, where compose runs the worker with
# AGENT_GRPC_USE_TLS=false over a shared loopback and no SSL context is ever built.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create non-root user (Debian/Ubuntu adduser, not the busybox flags Alpine used)
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

# Copy the built jar
COPY --from=builder /app/agent-worker/build/libs/*.jar app.jar

# Queue consumer: nothing to publish. The embedded server exists only for the endpoint below.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8089/actuator/health || exit 1

# Run the application with virtual threads enabled
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]
