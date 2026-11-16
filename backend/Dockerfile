# ==========================================
# Stage 1: Build the application
# ==========================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy the pom.xml and download dependencies first (for Docker layer caching)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy the source code and build the JAR
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ==========================================
# Stage 2: Create the lightweight runtime image
# ==========================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy the built JAR from the build stage
# Using *.jar ensures we don't have to hardcode the version number
COPY --from=build /app/target/*.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

# The crucial fix for Render: MaxRAMPercentage limits JVM heap to 75% of 512MB
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom -jar app.jar"]