FROM gradle:9.1.0-jdk25 AS build
WORKDIR /app

# Copy Gradle configuration files
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Copy source code
COPY src ./src

# Build the application
RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose the application port
EXPOSE 8081

# Set the entrypoint
ENTRYPOINT ["java", "-jar", "app.jar"]
