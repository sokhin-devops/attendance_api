# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Copy the POM first so dependency resolution is cached across source-only changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- run ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S attendance && adduser -S attendance -G attendance
COPY --from=build /build/target/*.jar app.jar
USER attendance
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
