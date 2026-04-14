FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .
RUN ./mvnw -B -pl linkpeek-server -am package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/linkpeek-server/target/linkpeek-server-0.1.0-SNAPSHOT.jar /app/app.jar

VOLUME /data/cache
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
