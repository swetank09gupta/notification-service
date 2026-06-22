# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies first (layer cache)
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/notification-service-*.jar app.jar

EXPOSE 8080

# Java 21 virtual threads + GraalVM-friendly options
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-Xmx512m", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
