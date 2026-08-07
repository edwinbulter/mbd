FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle/ gradle/
COPY gradlew gradlew
RUN chmod +x gradlew
COPY . .
RUN ./gradlew :portfolio-service:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/portfolio-service/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
