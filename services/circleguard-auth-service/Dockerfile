FROM gradle:8-jdk21 AS builder
WORKDIR /app
COPY . .
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :services:circleguard-auth-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/services/circleguard-auth-service/build/libs/*.jar app.jar
EXPOSE 8180
ENTRYPOINT ["java", "-jar", "app.jar"]
