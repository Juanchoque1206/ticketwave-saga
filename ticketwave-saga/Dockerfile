FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY ticketwave-saga/pom.xml ticketwave-saga/pom.xml
RUN mvn -q -DskipTests dependency:go-offline -pl ticketwave-saga
COPY ticketwave-saga ./ticketwave-saga
RUN mvn -q -DskipTests package -pl ticketwave-saga

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/ticketwave-saga/target/ticketwave-saga-*.jar app.jar
EXPOSE 8081
ENV SPRING_PROFILES_ACTIVE=rabbitmq
ENTRYPOINT ["java", "-jar", "/app/app.jar"]