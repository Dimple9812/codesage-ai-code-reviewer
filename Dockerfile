# syntax=docker/dockerfile:1
# Single-image build (frontend bundled into the Spring Boot app) for one-service hosts such as Render.
# For local development use docker-compose.yml, which runs nginx + backend separately.

FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /workspace
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/codesage-backend.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S codesage && adduser -S codesage -G codesage
WORKDIR /app
COPY --from=backend /workspace/target/extracted/dependencies/ ./
COPY --from=backend /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=backend /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=backend /workspace/target/extracted/application/ ./
USER codesage
# Tuned for small (512 MB) free-tier instances; override JAVA_OPTS on bigger machines.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
