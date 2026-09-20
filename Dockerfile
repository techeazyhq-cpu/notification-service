# Copyright 2026 Vasantha Kumar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author Vasantha Kumar <vasantha.kumar@hotmail.com>
# Builds any deployable module:  docker build --build-arg MODULE=client-api -t notification-client-api .
FROM maven:3-eclipse-temurin-24 AS build
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

FROM eclipse-temurin:24-jre
ARG MODULE
RUN useradd --system --uid 1001 app
USER app
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
