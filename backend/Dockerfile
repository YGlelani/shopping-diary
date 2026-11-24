
# ==========================================
# Stage 1: Build Spring Boot application
# ==========================================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Cache Maven dependencies
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy source files
COPY src ./src

# Build application
RUN mvn -B clean package -DskipTests


# ==========================================
# Stage 2: Production runtime
# ==========================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root application user
RUN addgroup -S appgroup && \
    adduser -S appuser -G appgroup

# Copy Spring Boot JAR
COPY --from=build /app/target/*.jar /app/app.jar

# Application configuration
ENV SERVER_PORT=8080

# JVM memory management for small containers
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

# Expose Spring Boot port
EXPOSE 8080

# Run as non-root user
USER appuser

# Start application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
