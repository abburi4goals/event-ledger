# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /build

# Copy parent POM first so Maven can resolve the multi-module layout
COPY pom.xml /build/pom.xml
COPY event-gateway/pom.xml /build/event-gateway/pom.xml
COPY account-service/pom.xml /build/account-service/pom.xml

# Pre-download dependencies (layer-cache friendly)
RUN mvn -pl event-gateway -am dependency:go-offline -q

# Copy source and build
COPY event-gateway/src /build/event-gateway/src
RUN mvn -pl event-gateway -am package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/event-gateway/target/event-gateway-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
