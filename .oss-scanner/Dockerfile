# AgiMate backend (https://github.com/AgiMateIo/backend): Java 25 / Spring Boot 4 / Gradle.
#
# The scanner builds this file with the repository as the build context, then removes the network.
# Everything the offline audit needs — the Gradle distribution, Maven Central and JitPack artifacts,
# and the Java 25 toolchain — must be fetched here, while the network is still available.
FROM eclipse-temurin:25-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_HOME=/opt/java/openjdk

RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# scanner contract: the checkout lives inside the image, at /src
COPY . /src
WORKDIR /src

# Online phase: resolve the Gradle distribution and every dependency, compile main and test
# sources. `assemble testClasses` is used instead of `build` so that one flaky test cannot fail the
# image; the tests run next, with a warning rather than a hard failure.
RUN cd services \
    && ./gradlew --no-daemon --console=plain assemble testClasses

# Warm the test runtime (JUnit, Mockito, test fixtures). A failing test does not abort the image:
# the scanner's agent can still run it and see the failure.
RUN cd services \
    && ./gradlew --no-daemon --console=plain test \
    || echo "WARNING: the online test run reported failures"

# From this point the scanner has no network. Make every later Gradle invocation offline, so a
# forgotten --offline cannot stall on a repository lookup.
RUN mkdir -p /root/.gradle/init.d \
    && echo 'gradle.startParameter.offline = true' > /root/.gradle/init.d/offline.gradle

# Prove the offline path: a clean compile and test run from the warmed cache. Individual tests can
# be rerun afterwards; offline is already the default in this image.
RUN cd services \
    && ./gradlew --no-daemon --console=plain clean test \
    || echo "WARNING: the offline test run reported failures"
