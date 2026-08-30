# Build the boot jar against the whole monorepo, then run it on a slim JRE.
FROM gradle:9.1.0-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle :services:operations-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /home/gradle/project/services/operations/build/libs/*.jar app.jar
EXPOSE 8004
HEALTHCHECK --interval=10s --timeout=3s --retries=10 --start-period=30s CMD curl -f http://localhost:8004/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
