FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -pl :tiko-runtime,:tiko-config,:tiko-processor,:tiko-kafka,:tiko-kafka-processor -am install -DskipTests
WORKDIR /src/tiko-examples-gradle/kotlin-transactional-outbox
RUN ./gradlew --no-daemon :orders:installDist :warehouse:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data && chown 10001:10001 /data
COPY --from=build /src/tiko-examples-gradle/kotlin-transactional-outbox/orders/build/install/orders /app/orders
COPY --from=build /src/tiko-examples-gradle/kotlin-transactional-outbox/warehouse/build/install/warehouse /app/warehouse
USER 10001:10001
