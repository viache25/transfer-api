# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

COPY src ./src
# Only assemble the executable Spring Boot jar. Tests are CI's job (CD publishes
# only commits CI already tested), and the Testcontainers-based integration and
# API tests could not run here anyway: there is no Docker daemon inside
# `docker build`. `bootJar` alone also skips the plain `-plain.jar`, so exactly
# one jar ends up in build/libs/ for the COPY below.
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
