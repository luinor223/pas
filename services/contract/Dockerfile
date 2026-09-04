# Build the boot jar against the whole monorepo, then run it on a slim JRE.
FROM gradle:9.1.0-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,id=pas-gradle-cache,target=/home/gradle/.gradle,sharing=shared,uid=1000,gid=1000 \
    gradle :services:contract-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /home/gradle/project/services/contract/build/libs/*.jar app.jar
EXPOSE 8002
ENTRYPOINT ["java", "-jar", "app.jar"]
