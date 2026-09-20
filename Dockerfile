# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

COPY src ./src
RUN chmod +x gradlew && ./gradlew build --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /workspace/build/libs/*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
