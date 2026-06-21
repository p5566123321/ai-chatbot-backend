FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

RUN java -Djarmode=layertools -jar build/libs/*.jar extract --destination build/extracted

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

COPY --from=builder /workspace/build/extracted/dependencies/ ./
COPY --from=builder /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/build/extracted/application/ ./

EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
