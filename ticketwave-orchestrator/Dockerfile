FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ticketwave-orchestrator/pom.xml ticketwave-orchestrator/pom.xml
RUN mvn -q -DskipTests dependency:go-offline -pl ticketwave-orchestrator
COPY ticketwave-orchestrator ./ticketwave-orchestrator
RUN mvn -q -DskipTests package -pl ticketwave-orchestrator

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/ticketwave-orchestrator/target/ticketwave-orchestrator-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]