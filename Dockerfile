# Stage 1: Build the application using Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy pom.xml and dependency download phase for layer caching
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build final executable package
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Lightweight JRE runtime
FROM eclipse-temurin:17-jre-alpine AS runner
WORKDIR /app

# Install curl for healthchecks
RUN apk add --no-cache curl

# Create non-root system user and group for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser:appgroup

# Copy jar from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Expose application port
EXPOSE 8081

# Environment settings
ENV PORT=8081 \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Healthcheck configuration
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8081/v1/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
