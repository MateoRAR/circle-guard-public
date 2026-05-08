FROM gradle:8-jdk21 AS builder
WORKDIR /app
COPY . .
RUN sed -i 's/\r//' gradlew && chmod +x gradlew
RUN ./gradlew :services:circleguard-gateway-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/services/circleguard-gateway-service/build/libs/*.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
