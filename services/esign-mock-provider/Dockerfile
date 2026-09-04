FROM gradle:9.1.0-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,id=pas-gradle-cache,target=/home/gradle/.gradle,sharing=locked,uid=1000,gid=1000 \
    gradle :services:esign-mock-provider-service:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /home/gradle/project/services/esign-mock-provider/build/libs/*.jar app.jar
EXPOSE 9001
ENTRYPOINT ["java", "-jar", "app.jar"]
