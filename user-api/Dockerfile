FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xmx384m"
COPY pom.xml ./
COPY deploy/production/maven-settings.xml /tmp/maven-settings.xml
COPY admin-api/pom.xml admin-api/pom.xml
COPY user-api/pom.xml user-api/pom.xml
COPY merchant-api/pom.xml merchant-api/pom.xml
COPY rider-api/pom.xml rider-api/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY user-api/src user-api/src
RUN --mount=type=cache,target=/root/.m2 mvn -s /tmp/maven-settings.xml -B -DskipTests -pl user-api -am package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/user-api/target/user-api-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
