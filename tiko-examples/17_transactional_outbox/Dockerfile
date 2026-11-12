FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -pl :outbox-orders,:outbox-warehouse -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data && chown 10001:10001 /data
COPY --from=build /src/tiko-examples/17_transactional_outbox/orders/target/outbox-orders.jar /app/orders.jar
COPY --from=build /src/tiko-examples/17_transactional_outbox/warehouse/target/outbox-warehouse.jar /app/warehouse.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar"]
