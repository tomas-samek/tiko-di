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
- Each request runs as one unit of work across suspension (`inEventScope` from
  `tiko-coroutines`, in `Routes.kt`), so the EVENT-scoped `RequestId` is fresh per request and
  the same on every dispatcher the route moves to. The service, a SINGLETON, reads it through a
  generated proxy. The blocking JDBC work runs on `Dispatchers.IO`, inside the unit.
- `GET /tickets/summary` counts each reporter's tickets with `map { async { ... } }`: concurrent
  work, so each count runs in a unit of work of its own, with its own `RequestId`.
- The module installs Ktor's `HttpRequestLifecycle` with `cancelCallOnClose`, so a client that
  disconnects cancels the route and the request's unit of work closes.
- `TicketApiTest` drives the routes in Ktor's `testApplication` against a `@TikoTest` container
  and swaps the `Notifier` for a recording one with `@TestComponent(Notifier::class)`. Its
  `@TikoTestOptions` method points the container at `application-test.yaml`.

```
cd tiko-examples-gradle/kotlin-ktor-exposed
./gradlew run                     # serves http://localhost:8080/tickets (HTTP_PORT to change)
curl -X POST localhost:8080/tickets -H 'Content-Type: application/json' \
     -d '{"title":"Printer on fire","reporter":"alice"}'
curl localhost:8080/tickets
./gradlew run --args=--smoke      # opens and lists one ticket over HTTP, then exits
```

- `TicketNotifications.onOpened` is a `suspend` `@EventHandler`: Tiko runs it as a coroutine of
  the container, off the request, and `close()` waits for it.

The pattern is written up in the [Ktor cookbook](../docs/cookbooks/ktor.md).

## kotlin-kafka-consumer &nbsp;<sub>[`kotlin-kafka-consumer/`](./kotlin-kafka-consumer)</sub>

A Kafka consumer: `payments` in, `receipts` out.

- `PaymentConsumer` bridges the `payments` topic with `@KafkaSource`. Its bridge is a `suspend fun`
  that records each payment in the `Ledger`, a suspending call, and publishes `PaymentRecorded`.
  Tiko runs it on the consumer thread in a unit of work of its own and commits the offset after it.
- `PaymentHandler` lets each payment through once. Kafka delivers at least once, so a redelivered
  payment comes back marked as a duplicate, and the `FirstDeliveryOnly` guard stops it.
- `ReceiptPublisher` sends one `Receipt` per new payment to `receipts` with `@KafkaSink`, keyed
  by order.
- Payloads are `@JvmRecord data class`es. A plain `data class` payload is a compile error under
  the default JSON serializer.
- `PaymentFlowTest` runs the whole flow on the in-memory `FakeKafkaBroker`, without Docker. It is
  a `@TikoTest` whose `@TikoTestOptions` method replaces the Kafka transport with one over the
  fake broker.

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
