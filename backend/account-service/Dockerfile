FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN gradle wrapper --gradle-version=8.2
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
