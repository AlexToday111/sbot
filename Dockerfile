FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system workout && useradd --system --gid workout workout
WORKDIR /app
COPY --from=build /build/target/workout-tracker-0.1.0.jar app.jar
USER workout
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
