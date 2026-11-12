# Tiko Kotlin examples (Gradle + KSP)

Each directory is a standalone Gradle build with its own wrapper. The builds resolve Tiko
from `mavenLocal()`, so install the framework once from the repo root first:

```
mvn install -DskipTests
```

Setup, construct rules and nullability rules: [`reference/kotlin.md`](../.ai-skills/tiko-build/reference/kotlin.md).

## kotlin-quickstart &nbsp;<sub>[`kotlin-quickstart/`](./kotlin-quickstart)</sub>

The smallest Kotlin service: constructor injection between Kotlin classes, `internal` components,
`@Produces` (including a nullable one), an `@EventTrigger` chain, and a `@TikoTest` with a
`@TestComponent` override.

```
cd tiko-examples-gradle/kotlin-quickstart
./gradlew build run
```

## kotlin-ktor-exposed &nbsp;<sub>[`kotlin-ktor-exposed/`](./kotlin-ktor-exposed)</sub>

A REST API over a database: Ktor routes, Exposed over a HikariCP pool (H2 in memory by default),
and Tiko holding the components in between.

- `Persistence` produces the `DataSource` and the Exposed `Database`; the container closes the pool
  on shutdown. `DbConfig` and `HttpConfig` are `@JvmRecord` `@Configuration` classes bound from
  `application.yaml`.
- `TicketService` is plain business logic. It stores the ticket and publishes `TicketOpened`;
  `TicketNotifications` handles the event.
- Each request runs as one unit of work (`Container.unitOfWork` in `Routes.kt`), so the
  EVENT-scoped `RequestId` is fresh per request. The service, a SINGLETON, reads it through a
  generated proxy.
- `TicketApiTest` drives the routes in Ktor's `testApplication` against a `@TikoTest` container
  and swaps the `Notifier` for a recording one with `@TestComponent(Notifier::class)`.

```
cd tiko-examples-gradle/kotlin-ktor-exposed
./gradlew run                     # serves http://localhost:8080/tickets (HTTP_PORT to change)
curl -X POST localhost:8080/tickets -H 'Content-Type: application/json' \
     -d '{"title":"Printer on fire","reporter":"alice"}'
curl localhost:8080/tickets
./gradlew run --args=--smoke      # opens and lists one ticket over HTTP, then exits
```

Tiko does not support coroutines yet: components and handlers are blocking code, and the EVENT
scope is bound to a thread. `unitOfWork` runs the blocking part on `Dispatchers.IO` inside one
scope; read the request before it and respond after it.

## kotlin-kafka-consumer &nbsp;<sub>[`kotlin-kafka-consumer/`](./kotlin-kafka-consumer)</sub>

A Kafka consumer: `payments` in, `receipts` out.

- `PaymentConsumer` bridges the `payments` topic into `PaymentReceived` events with `@KafkaSource`.
- `PaymentHandler` records each payment in the `Ledger`. Kafka delivers at least once, so a
  redelivered payment comes back marked as a duplicate, and the `FirstDeliveryOnly` guard stops
  it from going further.
- `ReceiptPublisher` sends one `Receipt` per new payment to `receipts` with `@KafkaSink`, keyed
  by order.
- Payloads are `@JvmRecord data class`es. A plain `data class` payload is a compile error under
  the default JSON serializer.
- `PaymentFlowTest` runs the whole flow on the in-memory `FakeKafkaBroker`, without Docker.

```
cd tiko-examples-gradle/kotlin-kafka-consumer
./gradlew build                   # includes PaymentFlowTest
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.2
./gradlew run                     # KAFKA_BOOTSTRAP to point elsewhere
```

Then, from another terminal:

```
docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic payments <<< '{"paymentId":"pay-1","orderId":"order-7","amountCents":4200}'
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic receipts --from-beginning --property print.key=true
```

## kotlin-transactional-outbox &nbsp;<sub>[`kotlin-transactional-outbox/`](./kotlin-transactional-outbox)</sub>

Orders and warehouse services use file-backed H2, Kafka and Tiko's KSP wiring. Each service
commits its business change and outbox row together. The Docker-backed test kills the orders
JVM after commit and verifies recovery and deduplication. See the example README for Docker
Compose and local run commands.
