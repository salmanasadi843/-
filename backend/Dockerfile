FROM gradle:9.3.1-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle -p backend --no-daemon clean installDist

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/build/install/darsyar-backend/ ./
ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
USER 10001
ENTRYPOINT ["/app/bin/darsyar-backend"]
