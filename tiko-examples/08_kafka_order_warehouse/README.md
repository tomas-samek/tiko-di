# 08 — Kafka order/warehouse (cross-JVM)

Demonstrates Tiko's universal transport-adapter pattern: an `order-service` JVM publishes
`OrderPlaced` events; a `warehouse-service` JVM in a separate process receives them via
Kafka and handles them like any other local event.

Same `@EventHandler` shape on both sides. The only Kafka-specific code lives in the two
bridge files — one `@KafkaSink` in `order-service`, one `@KafkaSource` in `warehouse-service`.

Companion to [`05_multi_module`](../05_multi_module), which aggregates two modules in a
single JVM via `AggregatingContainer`. Same domain split; opposite deployment topology.

## Run locally

```bash
# 1. Start Kafka (single-broker KRaft mode)
docker compose -f tiko-examples/08_kafka_order_warehouse/docker-compose.yml up -d kafka

# 2. Build both shaded JARs
mvn -pl tiko-examples/08_kafka_order_warehouse -am package

# 3. Run warehouse-service (waits for orders)
java -jar tiko-examples/08_kafka_order_warehouse/warehouse-service/target/kafka-warehouse-service-0.1.0.jar &

# 4. Run order-service (CLI prompts for amounts)
java -jar tiko-examples/08_kafka_order_warehouse/order-service/target/kafka-order-service-0.1.0.jar
# Type "19.99" + ENTER → watch warehouse-service log "warehouse received: …"
```

## Layout

| Path | What's there |
|---|---|
| `shared-events/` | `OrderPlaced` record — used by both services as-is |
| `order-service/` | `OrderService` (creates order), `OrderKafkaPublisher` (`@KafkaSink`), `Main` |
| `warehouse-service/` | `WarehouseService` (`@EventHandler` on `OrderPlaced`), `OrderKafkaConsumer` (`@KafkaSource` + `@EventTrigger`), `Main` |
| `e2e/` | Testcontainers + `ProcessBuilder` test asserting cross-JVM message flow |
| `docker-compose.yml` | Local Kafka broker; service images are added once they exist |

## How to read the code

Open these two files side-by-side:

- `order-service/.../OrderKafkaPublisher.java` — `@KafkaSink` only (no `@EventHandler` — the runtime subscribes its own callback)
- `warehouse-service/.../WarehouseService.java` — `@EventHandler` only

The handler in `warehouse-service` has no Kafka import. It receives `OrderPlaced` events
the same way it would in a single-JVM app — the bridge (`OrderKafkaConsumer.java`) feeds
the bus from Kafka transparently.

## What this proves

- One handler signature works for both transports.
- Each service is its own deployable unit (separate JVM, separate shaded jar, separate process).
- The bridge code is small and confined to one file per direction.
- Compile-time validation catches mistakes: try removing `@EventTrigger` from `OrderKafkaConsumer.java` and run `mvn compile` — the build fails with a clear pointer to the missing sibling annotation.

## Testing without a broker

Two Docker-free integration tests show the supported fake-broker seam (#414):
`order-service/.../FakeBrokerOrderPublishIT` drives the `@KafkaSink` outbound path and
`warehouse-service/.../FakeBrokerWarehouseConsumeIT` the `@KafkaSource` inbound path, via
`TikoOptions.builder().replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))`.
The Testcontainers e2e (`OrderToWarehouseE2EIT`) still covers the real-broker path.

## Future docker images

Service images for `order-service` / `warehouse-service` will be added to
`docker-compose.yml` once published. Until then, run them as plain JVMs from the shaded jars.
