# Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/app-0.0.1-SNAPSHOT.jar app.jar
# Public deployment: no H2 web console; DB lives inside the container (resets on redeploy)
ENV SPRING_H2_CONSOLE_ENABLED=false \
    LOGGING_LEVEL_ORG_HIBERNATE_SQL=info \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
CMD ["java", "-jar", "app.jar"]
