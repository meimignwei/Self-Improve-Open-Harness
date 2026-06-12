# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY openharness-common/pom.xml openharness-common/
COPY openharness-config/pom.xml openharness-config/
COPY openharness-api/pom.xml openharness-api/
COPY openharness-engine/pom.xml openharness-engine/
COPY openharness-tools/pom.xml openharness-tools/
COPY openharness-extensions/pom.xml openharness-extensions/
COPY openharness-ui/pom.xml openharness-ui/
COPY openharness-cli/pom.xml openharness-cli/
COPY openharness-ohmo/pom.xml openharness-ohmo/
COPY openharness-observability/pom.xml openharness-observability/
COPY openharness-app/pom.xml openharness-app/

# Resolve dependencies (cache layer)
RUN mvn -B dependency:go-offline -q

COPY . .
RUN mvn -B package -DskipTests -pl openharness-app -am -q

# ---- Run stage ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S openharness && adduser -S openharness -G openharness
USER openharness

WORKDIR /app
COPY --from=build /workspace/openharness-app/target/openharness-app-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxHeapSize=1g", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
