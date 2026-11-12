# Cookbook: Kafka transport (`@KafkaSource` / `@KafkaSink`)

> Runnable example: [`tiko-examples/08_kafka_order_warehouse`](../../tiko-examples/08_kafka_order_warehouse).
> HTTP → database → Kafka including its failure paths (a broker outage, a crash between commit and
> publish, replayed and stale messages): [`tiko-examples/17_transactional_outbox`](../../tiko-examples/17_transactional_outbox).

Tiko's core is compile-time DI + an in-process event bus. A message broker is
intentionally out of scope of the core — but Tiko ships a **first-party Kafka
transport** (`tiko-kafka` + `tiko-kafka-processor`) that bridges Kafka topics to the
same local `EventBus` your `@EventHandler`s already use. You write ordinary handlers;
two annotations move records on and off Kafka.

## When to reach for it

Use the local bus (the default) for in-process choreography. Reach for the Kafka
transport when events must cross a process/service boundary, survive a restart, or be
consumed by another service. The handler code is identical either way — only the
edge (`@KafkaSource` in, `@KafkaSink` out) changes.

## What you'll wire

1. Enable the two Kafka modules.
2. **Inbound:** `@KafkaSource` bridges a topic into a local event.
3. **Outbound:** `@KafkaSink` publishes a local event to a topic.
4. Configure the broker under `tiko.kafka.*`.
5. Handle undeserializable ("poison") records.

## 1. Enable the modules

Add both the runtime and the annotation processor (the processor generates the
`KafkaTransportBootstrap` that `Tiko.create()` / `Tiko.daemon()` discovers via the
`TransportBootstrap` ServiceLoader SPI):

```xml
<dependency>
  <groupId>io.github.tomas-samek</groupId>
  <artifactId>tiko-kafka</artifactId>
  <version>${tiko.version}</version>
</dependency>
```
```xml
<!-- in maven-compiler-plugin <annotationProcessorPaths> -->
<path>
  <groupId>io.github.tomas-samek</groupId>
  <artifactId>tiko-kafka-processor</artifactId>
  <version>${tiko.version}</version>
</path>
```

**Packaging as a shaded fat jar:** keep `ServicesResourceTransformer` among the
`maven-shade-plugin` transformers. The transport, every module's `@Configuration`
binders (yours and `tiko-kafka`'s `tiko.kafka`) and every module's container are discovered
through `META-INF/services/` files, which that transformer merges (#531, #537). See
`tiko-examples/08_kafka_order_warehouse/*/pom.xml`.

Both artifacts are managed by `tiko-bom`; with the BOM imported, the explicit
`<version>` lines above can go.

## 2. Inbound — `@KafkaSource`

A `@KafkaSource` method polls a topic, deserializes each record into the method's
first parameter type, invokes the method, and publishes the **return value** onto the
local `EventBus` (dispatched **by return type**, not by name). A `null` return publishes nothing
and still commits the record (see [below](#which-failures-redeliver-a-record)).

**Rules (enforced at compile time):**
- The enclosing class is `@Component(scope = Scope.SINGLETON)` (consumer threads run
  outside any unit of work).
- The method must be **non-void** and carry a sibling **`@EventTrigger`**.
- Signature: `(Payload payload)` or `(Payload payload, KafkaContext ctx)`.

```java
@Component(scope = Scope.SINGLETON)
public class OrderIngress {

    // product-updates topic -> ProductUpdate event on the local bus
    @KafkaSource(topic = "product-updates")
    @EventTrigger(eventName = "ProductUpdate")
    public ProductUpdate onProductUpdate(ProductUpdate update) {
        return update;   // becomes the local event payload
    }
}
```

The emitted `ProductUpdate` is then handled like any local event:

```java
@Component(scope = Scope.SINGLETON)
public class ReferenceData {
    @EventHandler
    public void onProductUpdate(ProductUpdate u) { repository.upsert(u); }
}
```

### Which failures redeliver a record

The bridge and the sync handlers of its event run in one unit of work, but they don't fail
the same way. The offset acknowledges that the event reached the local bus, not what its
handlers did with it:

| What throws | What happens to the record |
|---|---|
| Deserialization, the bridge method, or the publish | It takes the ingest-error path ([section 5](#5-poison-messages-ingest-failures)). With the default `SEEK` it is redelivered. |
| Nothing throws, but the bridge returns `null` | The offset is committed and nothing is published. The record is not redelivered. |
| A sync `@EventHandler` of the emitted event, or a handler further down an `@EventTrigger` chain | The exception goes to the `ErrorHandler` and the offset is committed. The record is not redelivered. |
| An async handler (`async = true`) | The commit doesn't wait for the handler. Its failure, or a crash while it runs, is not redelivered. |

So moving a write from the bridge into a handler looks like an ordinary refactor, but it
changes what happens when the write fails. In the example above, a failed
`repository.upsert(u)` is logged and the update is lost.

Put work that must be redelivered when it fails in the bridge method, before it returns: the
write itself, or a record of the intent (an outbox row) that later work picks up. A redelivered
record runs the bridge again, so make that work idempotent. Keep in handlers the work whose
failure the `ErrorHandler` may log, or that you recover yourself.

A bridge exception is retried under the default `SEEK` until the bridge stops throwing. That is
right for a transient failure, such as a database that is briefly down. It is wrong for a failure
that retrying can't fix, such as a result for an order your database doesn't have: that record
blocks its partition for good, and every record behind it waits. So sort your failures into the two
kinds, and handle the permanent ones without throwing:

- **The bridge recognizes the case itself** (an unknown order, a value out of range): log it and
  return `null`. The record is consumed, nothing is published, and the offset moves on. In Kotlin,
  declare the return type nullable (`OrderPlaced?`).
- **The failure comes from deeper down** (a library, the serializer) or needs a dead-letter record:
  throw, or let it propagate, and register a
  [`KafkaIngestErrorDecider`](#programmatic-ingest-error-decisions-kafkaingesterrordecider) that
  skips or dead-letters the permanent exception types.

Either way, everything else is still sought back and retried.

The runnable example shows the decider form. In
[`tiko-examples/08_kafka_order_warehouse`](../../tiko-examples/08_kafka_order_warehouse),
`OrderKafkaConsumer` rejects an order with a non-positive amount by throwing
`RejectedOrderException`, and `WarehouseIngestPolicy` dead-letters it. `RejectedOrderDoesNotBlockPartitionIT`
shows that the valid order behind it on the same partition is processed. Without the decider, that
test times out.

`@KafkaSource` elements: `topic` (required); `consumerGroup` (default `""` → falls back
to `tiko.kafka.consumer-group`); `serializer` (default → `tiko.kafka.serializer`);
`commitMode` (`PER_RECORD`; the MVP supports per-record commits only).

## 3. Outbound — `@KafkaSink`

A `@KafkaSink` method is subscribed by the runtime to its **first parameter type**:
when such an event is published locally, the runtime invokes the method, serializes
the **return value**, and sends it to the topic.

**Rules:**
- Enclosing class is `@Component(scope = Scope.SINGLETON)`.
- **Do NOT also annotate with `@EventHandler`** — the runtime's `EventBus.subscribe()`
  is the exclusive hook; adding `@EventHandler` double-fires the method.
- `partitionKey` names a record-component accessor (or zero-arg public method) on the
  return type whose value becomes the Kafka message key; empty → null key (round-robin).

```java
@Component(scope = Scope.SINGLETON)
public class NotificationSink {

    @KafkaSink(topic = "notifications", partitionKey = "purchaseId")
    public Notification publish(Notification n) {
        return n;   // serialized and sent to "notifications", keyed by purchaseId
    }
}
```

So a typical join looks like: a `@KafkaSource` on `purchases` returns a `Purchase`
event; an `@EventHandler @EventTrigger` does the lookups and returns a `Notification`;
the `@KafkaSink` above forwards it to Kafka. Local `@EventHandler`s run **before** sink
callbacks, so a sink/broker failure doesn't block local processing.

## 4. Configuration

Broker settings bind to the `KafkaConfig` record under the `tiko.kafka` prefix, with
**kebab-case** keys: `bootstrap-servers` (default `localhost:9092`), `consumer-group`
(`tiko-app`), `serializer` (`json`), `auto-offset-reset` (`earliest`), `poll-timeout`,
`shutdown-timeout`, and pass-through `producer-properties` / `consumer-properties` maps.
The full key table, with defaults, is in the `tiko-build` skill's
[`reference/api-signatures.md`](../../.ai-skills/tiko-build/reference/api-signatures.md).

```yaml
tiko:
  kafka:
    bootstrap-servers: localhost:9092
    consumer-group: notify-service
    auto-offset-reset: earliest
```

> **Key casing:** `KafkaConfig` declares its public keys with `@Key("bootstrap-servers")`,
> so they are kebab-case. Your own `@Configuration` records bind to their component names
> exactly (camelCase as declared) unless a component carries `@Key`. A key that matches
> neither fails startup with a "did you mean …?" suggestion rather than binding silently.

### Swapping the serializer (the `EventSerializer` SPI)

Serialization is a transport-neutral SPI: `io.tiko.EventSerializer` (in `tiko-api`) — two methods,
`byte[] serialize(Object)` and `<T> T deserialize(byte[], Class<T>)`. A serializer written once
works across every transport (Kafka today; RabbitMQ / JMS as they land), so you don't fork an
adapter to change the wire format. The default is JSON (Jackson), registered under the name `json`.

To plug in a different format (Avro, Protobuf, a schema-registry client, ...):

1. Implement `EventSerializer` — or `KafkaSerializer` (the Kafka-named sub-interface) if you want it
   selectable by config name. A single instance handles every payload type via the method-level
   type parameter on `deserialize`, so you write one impl, not one per event class.
2. Register it for name-based lookup by shipping a `NamedKafkaSerializer` via
   `META-INF/services/io.tiko.kafka.NamedKafkaSerializer` whose `name()` is your config key.
3. Select it with `tiko.kafka.serializer: <name>` (whole transport) or per-bridge with
   `@KafkaSource(serializer = MyAvroSerializer.class)` / `@KafkaSink(serializer = ...)`.

In `deserialize`, throw `io.tiko.UndecodablePayloadException` for input that can never be
decoded (malformed bytes, a type mismatch), and any other exception for a failure that may
pass on retry (the schema registry is unreachable). Error policies rely on that split. Keep
payload values out of both: error handlers log the message and the whole cause chain.

An unknown serializer name fails fast at container start, naming the missing serializer and the
YAML key.

## 5. Poison messages (ingest failures)

A handler of the emitted event that throws is not an ingest failure: its record is committed
and nothing in this section applies (see
[Which failures redeliver a record](#which-failures-redeliver-a-record)).

When a record fails to ingest — deserialize, bridge dispatch, or publish — the runner
routes a `KafkaIngestError` to your `ErrorHandler`, then applies the configured
**`tiko.kafka.poison-record-policy`**:

```yaml
tiko:
  kafka:
    poison-record-policy: SEEK   # default; or SKIP
```

- **`SEEK`** (default) — seek back to the failed offset and redeliver. No data is lost
  across a *transient* failure (a brief broker/schema-registry/DB blip rides through),
  but a genuinely bad ("poison") record blocks its partition until it is removed or the
  consumer is reconfigured. Before each redelivery the runner pauses **only that
  partition** for a backoff that doubles per consecutive failure of the same record —
  so a poison record settles to one retry (and one `ErrorHandler` call) per
  `seek-backoff-max`, and the topic's other partitions keep flowing:

  ```yaml
  tiko:
    kafka:
      seek-backoff: PT0.5S      # first retry delay (default); PT0S redelivers immediately
      seek-backoff-max: PT30S   # cap (default); must not be less than seek-backoff
  ```

  A negative value, or a cap below `seek-backoff`, fails at startup with an error naming
  the key, as does a `poll-timeout` that isn't positive.
- **`SKIP`** — log via the `ErrorHandler` (above) and commit past the record so the
  partition advances. This is the first-class "log and skip a poison record" — no
  `null`-returning serializer workaround needed.

**Choosing:** the runner cannot tell a permanent poison record from a transient blip at
the moment of failure — and deserialization is *not* exempt (a schema-registry
deserializer does a network call; a rolling deployment can make the same bytes fail now
and succeed minutes later). So `SKIP` also drops records that failed for a transient
reason. The one failure that is known to be permanent is a serializer-reported
`UndecodablePayloadException`: the bundled `JsonKafkaSerializer` decodes locally and
deterministically, so its decode failures repeat on every redelivery — a decider can
skip those without retrying (below). Enable it only for streams where occasional loss on a blip is acceptable; keep
the default `SEEK` when every record matters. To retry a bounded number of times and then
dead-letter, register a `KafkaIngestErrorDecider` (below).

## Programmatic ingest-error decisions (`KafkaIngestErrorDecider`)

`tiko.kafka.poison-record-policy` (`SEEK`/`SKIP`) is a uniform, zero-config
switch: every ingest failure is either sought back or skipped. When you need to
branch on *what* failed — retry a transient blip a few times, dead-letter a
known-bad shape, skip the rest — register a `KafkaIngestErrorDecider`.

Register **at most one** as a singleton component — registering more than one
fails fast at bootstrap. When present, it overrides the static policy for every
ingest failure; when absent, the static `poison-record-policy` runs unchanged
(this feature is purely additive).

```java
@Component(scope = Scope.SINGLETON)
public class OrderIngestPolicy implements KafkaIngestErrorDecider {
    @Override
    public IngestDecision decide(KafkaIngestError error, int attempt) {
        if (error.cause() instanceof io.tiko.UndecodablePayloadException
                || error.cause() instanceof RejectedOrderException) {
            return IngestDecision.DEAD_LETTER; // fails the same way every time — hand off now, no retry
        }
        return attempt < 5 ? IngestDecision.SEEK   // possibly transient — retry
                           : IngestDecision.DEAD_LETTER;
    }
}
```

`UndecodablePayloadException` (in `tiko-api`) is the serializer's statement that the bytes
can never be decoded into the target type. The bundled JSON serializer raises it for every
decode failure; it carries the target type (`targetType()`) and no cause or payload value.
For a decode failure, `error.cause()` is that exception itself, so test it with
`instanceof` — never by matching the message text. The same holds for an exception your bridge
throws: `RejectedOrderException` above is the bridge's own exception for an order that can never be
fulfilled.

`attempt` is the consecutive failure count for the record's offset, starting at
`1`; bounding the retry is your code's job. The outcomes:

| Outcome       | Effect |
|---------------|--------|
| `SEEK`        | Seek back; the record is redelivered on the next poll. No offset committed. |
| `SKIP`        | Route the `KafkaIngestError`; commit past the record. |
| `DEAD_LETTER` | Route a `KafkaRecordDeadLettered` (carrying `attempts`) instead of `KafkaIngestError`; commit past. Forward it from your `ErrorHandler` to whatever dead-letter sink you run. |
| `FAIL`        | Route the `KafkaIngestError`; stop **this topic's** consumer (the record is left uncommitted and redelivers if the consumer restarts). |

There is no dead-letter *topic* — `DEAD_LETTER` routes a distinct
`ErrorContext` through your `ErrorHandler`, which is where you decide what to do
with it:

```java
TikoOptions.builder().errorHandler(ctx -> {
    switch (ctx) {
        case KafkaRecordDeadLettered dl -> dlqSink.send(dl);
        case KafkaIngestError e -> log.warn("ingest failure on {}", e.topic());
        default -> { /* other contexts */ }
    }
}).build();
```

A decider that throws falls back to `SEEK` (no data loss) and logs a warning; it
never kills the consumer thread.

## Trade-offs (MVP)

- Per-record commit only (`commitMode = PER_RECORD`).
- Poison handling is the static skip-or-seek policy (§5), or per-error decisions (bounded
  retry, dead-letter) via `KafkaIngestErrorDecider`.

## Beyond

See the full worked service in
[`tiko-examples/08_kafka_order_warehouse`](../../tiko-examples/08_kafka_order_warehouse),
and [`persistence.md`](./persistence.md) for the `@Produces DataSource` pattern these
handlers typically write to.
