# Transactional outbox across two Tiko services

The orders service accepts an HTTP request and commits the order and an outbox row in one local
transaction. The warehouse service reserves stock, records the decision against the order ID, and
queues the result in its own outbox, also in one local transaction. The orders service receives the
result through Kafka and updates the order.

Each process has its own file-backed H2 database and one relay. Both use Tiko for dependency
injection, configuration, lifecycle, and Kafka ingress. The relay calls the Kafka producer directly
and waits for broker acknowledgement before deleting the pending row.

## Run the stack with Docker Compose

From the repository root:

```sh
cd tiko-examples/17_transactional_outbox
docker compose up --build -d
docker compose logs -f orders warehouse
```

Compose builds both service JARs and starts both services and a single Kafka broker. Only the
order HTTP port is published, on `127.0.0.1:8080`. The databases and Kafka data use separate
named volumes that survive container restarts and `docker compose down`.

## Run the services as local JVMs

From the repository root, with JDK 21+, Maven, and Docker:

```sh
docker run --rm --name tiko-outbox-kafka -p 9092:9092 apache/kafka:3.9.2
```

In another terminal, build and run the services in separate shells, both from the repository root:

```sh
mvn -pl :outbox-e2e -am package
java -jar tiko-examples/17_transactional_outbox/warehouse/target/outbox-warehouse-0.6.1-SNAPSHOT.jar
java -jar tiko-examples/17_transactional_outbox/orders/target/outbox-orders-0.6.1-SNAPSHOT.jar
```

The warehouse starts with ten units of `widget`. Submit an order and query its state:

```sh
curl -i -X PUT http://127.0.0.1:8080/orders/9d798d07-12bc-4198-9aac-25b2f5d2eedd \
  -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":2}'
curl http://127.0.0.1:8080/orders/9d798d07-12bc-4198-9aac-25b2f5d2eedd
```

`PUT` returns `202`. Poll `GET` for the transition from `PENDING` to `RESERVED` or `REJECTED`.
Repeating the same ID and payload reuses the order. Reusing the ID with a different payload returns
`409`. Use a new UUID to place a new order.

`KAFKA_BOOTSTRAP` defaults to `localhost:9092`. The order server listens on loopback and accepts
`ORDERS_HTTP_PORT`. `ORDERS_DB_URL` and `WAREHOUSE_DB_URL` override the JDBC URLs. By default,
both database files live under `target/outbox-data` in the working directory and survive process
restarts. Use dedicated database paths if running multiple copies of the demo.

## Delivery and transaction boundaries

| Boundary | Behavior |
|---|---|
| HTTP acceptance | The orders transaction has committed before the response. Kafka may still be unavailable. |
| Outbox relay | A failed send or acknowledgement leaves the row pending. A crash after broker acknowledgement and before row deletion may publish the row twice. |
| Kafka ingress | Each bridge commits its local database work before returning. Tiko closes its `EVENT` scope and then commits the Kafka offset. A bridge exception reaches the Kafka runner, which uses the default `SEEK` policy to retry. |
| Warehouse deduplication | The unique order ID in `reservations` is the durable deduplication key. The stock update, recorded decision and result outbox row share one transaction. |
| Order result | Applying the same terminal decision again has no effect. A missing order or conflicting decision is an ingest failure. |

The JDBC transactions are explicit application code. An `EVENT` scope defines the DI and event
lifecycle. The database change is inside `@KafkaSource`, where a thrown failure prevents offset
commit on that delivery. A later bus handler has separate error handling, so the example keeps
the acknowledgement-critical write at the bridge boundary.

The example assumes one relay per database and uses one pending row at a time. A relay that
cannot publish its oldest row retries it and delays later rows. The Kafka demo runs a single broker.
Scaling the relay, retention and operator-driven replay, credentials, and schema evolution depend
on the production deployment.

## Reproduce the failure with Compose

Run these commands from the example directory with the stack already up:

```sh
docker compose stop kafka
curl -i -X PUT http://127.0.0.1:8080/orders/0c7d97ca-8cde-4f24-a89f-1ec6d15650fb \
  -H 'Content-Type: application/json' -d '{"sku":"widget","quantity":1}'
docker compose kill orders
docker compose up -d orders
curl http://127.0.0.1:8080/orders/0c7d97ca-8cde-4f24-a89f-1ec6d15650fb
```

Wait for the `202` before killing orders. Use a fresh UUID on each run. Starting orders brings
Kafka back up, and the pending order should reach a terminal decision after the relay retries.
The warehouse service reconnects to the same broker.

## Verify failure recovery

```sh
mvn -pl :outbox-e2e -am verify
```

The database tests run without Docker. The Testcontainers integration test starts a real broker
and forks separate JVMs for the two services. It commits an HTTP order while the order process
cannot reach Kafka, kills that process, and checks that both the order and its outbox row survived.
It restarts the service against the broker, waits for the warehouse decision over Kafka, then
delivers the same order again and checks the consumer offset and persisted stock.
