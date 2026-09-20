# Builds any deployable module:  docker build --build-arg MODULE=client-api -t notification-client-api .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY notification-core/pom.xml notification-core/
COPY client-api/pom.xml client-api/
COPY admin-api/pom.xml admin-api/
COPY dispatcher/pom.xml dispatcher/
COPY billing/pom.xml billing/
COPY db-migration/pom.xml db-migration/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline -pl ${MODULE} -am || true
COPY notification-core/src notification-core/src
COPY billing/src billing/src
COPY ${MODULE}/src ${MODULE}/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package -pl ${MODULE} -am

FROM eclipse-temurin:21-jre
ARG MODULE
RUN useradd --system --uid 1001 app
USER app
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
