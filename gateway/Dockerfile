FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xmx384m"
COPY pom.xml ./
COPY admin-api/pom.xml admin-api/pom.xml
COPY user-api/pom.xml user-api/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY gateway/src gateway/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests -pl gateway -am package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/gateway/target/gateway-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
