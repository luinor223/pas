FROM gradle:9.1.0-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle :services:operations-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /home/gradle/project/services/operations/build/libs/*.jar app.jar
EXPOSE 8004 50054
ENTRYPOINT ["java", "-jar", "app.jar"]
