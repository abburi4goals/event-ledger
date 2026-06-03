# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml /build/pom.xml
COPY event-gateway/pom.xml /build/event-gateway/pom.xml
COPY account-service/pom.xml /build/account-service/pom.xml

RUN mvn -pl account-service -am dependency:go-offline -q

COPY account-service/src /build/account-service/src
RUN mvn -pl account-service -am package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/account-service/target/account-service-*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
