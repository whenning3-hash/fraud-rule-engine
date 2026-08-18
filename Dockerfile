# Pre-built JAR pattern — build locally with Maven, copy JAR into the image.
# Spring Boot 4.1.0 is not yet on Maven Central; build with the local Maven cache first:
#   mvn clean package -DskipTests
#   docker compose up --build

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY target/fraud-rule-engine-1.0.0.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Dspring.profiles.active=local", \
  "-jar", "app.jar"]
