# Transactional outbox in Kotlin

This is the Gradle and KSP version of the [Java transactional outbox example](../../tiko-examples/17_transactional_outbox).
Orders accepts HTTP requests through Javalin. Orders and warehouse each commit their business
change and outbox row in one file-backed H2 transaction. The relay waits for Kafka to acknowledge
an event before deleting the outbox row. It sends pending rows in order and waits between polls
only when the outbox is empty or a send fails. The warehouse uses the order ID to deduplicate replay.

Tiko generates wiring for each application with KSP. The Kafka payloads and typed configuration
are Kotlin `@JvmRecord` data classes. Kafka ingress commits the business transaction inside
`@KafkaSource`, so a failure can reach the Kafka runner before it commits the offset. The orders bridge logs and skips
results for missing orders or conflicting decisions so those messages cannot block later results.

## Run with Docker Compose

From this directory:

```sh
docker compose up --build -d
docker compose logs -f orders warehouse
```

The orders HTTP API is on `127.0.0.1:8081`. Kafka and both databases use named volumes. To try the
recovery path, stop Kafka, submit an order, and kill orders after HTTP returns `202`. Restart orders
with `docker compose up -d orders`, which also starts Kafka, and poll the order status.

```sh
curl -i -X PUT http://127.0.0.1:8081/orders/9d798d07-12bc-4198-9aac-25b2f5d2eedd \
  -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":2}'
curl http://127.0.0.1:8081/orders/9d798d07-12bc-4198-9aac-25b2f5d2eedd
```

The response moves from `PENDING` to `RESERVED` or `REJECTED`. Submitting the same ID and payload
again returns the existing order. Submitting that ID with a different payload returns `409`.
Use a fresh UUID for each recovery run.

## Run locally and verify

With JDK 21, install the Tiko snapshots using `mvn install -DskipTests` from the repository root.
Start a broker with `docker run --rm -p 9092:9092 apache/kafka:3.9.2`. From this directory, run the
services in separate shells:

```sh
./gradlew :warehouse:run
./gradlew :orders:run
```

For local JVMs, the API defaults to port 8080. Set `KAFKA_BOOTSTRAP` to use another broker, or
`ORDERS_HTTP_PORT` to change the port. The databases default to `build/outbox-data` relative to
each service's working directory. The default URLs keep H2 open between polls with
`DB_CLOSE_DELAY=-1`. `ORDERS_DB_URL` and `WAREHOUSE_DB_URL` override those URLs.

```sh
./gradlew :e2e:test -PrequireDocker
```

The test starts Kafka with Testcontainers, forks both Kotlin applications, and verifies that a
backlog of committed orders survives a forced JVM exit while Kafka is unreachable. After
restart, it checks that the valid results are applied even with a stale result ahead of them
in the same partition. It then replays an order and checks the consumer offset and stock.
Omitting `-PrequireDocker` allows the test to skip without Docker.
