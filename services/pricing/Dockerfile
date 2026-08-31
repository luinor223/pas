# Build the boot jar against the whole monorepo, then run it on a slim JRE.
FROM gradle:9.1.0-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle :services:pricing-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /home/gradle/project/services/pricing/build/libs/*.jar app.jar
EXPOSE 8003
ENTRYPOINT ["java", "-jar", "app.jar"]
