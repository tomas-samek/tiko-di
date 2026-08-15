# Cookbook: Kafka transport (`@KafkaSource` / `@KafkaSink`)

> Runnable example: [`tiko-examples/08_kafka_order_warehouse`](../../tiko-examples/08_kafka_order_warehouse).

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

> **Note:** as of writing, `tiko-kafka` / `tiko-kafka-processor` are not in
> `tiko-bom` `dependencyManagement` (see #298), so pin `${tiko.version}` explicitly
> on both until that lands.

## 2. Inbound — `@KafkaSource`

A `@KafkaSource` method polls a topic, deserializes each record into the method's
first parameter type, invokes the method, and publishes the **return value** onto the
local `EventBus` (dispatched **by return type**, not by name).

**Rules (enforced at compile time):**
- The enclosing class is `@Component(scope = Scope.SINGLETON)` (consumer threads run
  outside request/event scopes).
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

Broker settings bind to the `KafkaConfig` record under the `tiko.kafka` prefix.
Fields: `bootstrapServers` (default `localhost:9092`), `consumerGroup`
(`tiko-app`), `serializer` (`json`), `autoOffsetReset` (`earliest`),
`pollTimeout`, `shutdownTimeout`, and pass-through `producerProperties` /
`consumerProperties` maps.

```yaml
tiko:
  kafka:
    bootstrapServers: localhost:9092
    consumerGroup: notify-service
    autoOffsetReset: earliest
```

> **Key casing (resolved in #310):** config binding is **exact-key** — the YAML key
> must match the record field name verbatim. Use camelCase (`bootstrapServers`), not
> kebab-case (`bootstrap-servers`) or snake_case. A near-miss fails the build with a
> "did you mean 'bootstrapServers'?" suggestion rather than binding silently. Keep your
> own `@Configuration` records' keys aligned to their field names the same way.

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

An unknown serializer name fails fast at container start, naming the missing serializer and the
YAML key.

## 5. Poison messages (ingest failures)

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
  consumer is reconfigured.
- **`SKIP`** — log via the `ErrorHandler` (above) and commit past the record so the
  partition advances. This is the first-class "log and skip a poison record" — no
  `null`-returning serializer workaround needed.

**Choosing:** the runner cannot tell a permanent poison record from a transient blip at
the moment of failure — and deserialization is *not* exempt (a schema-registry
deserializer does a network call; a rolling deployment can make the same bytes fail now
and succeed minutes later). So `SKIP` also drops records that failed for a transient
reason. Enable it only for streams where occasional loss on a blip is acceptable; keep
the default `SEEK` when every record matters. A *safe* auto-skip that rides out transient
failures before giving up needs bounded retry, tracked separately (#108), as does a
dead-letter destination (#111).

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
        if (attempt < 3 && error.cause() instanceof java.io.IOException) {
            return IngestDecision.SEEK;        // transient — retry (redelivered next poll)
        }
        if (error.cause() instanceof com.example.SchemaMismatch) {
            return IngestDecision.DEAD_LETTER; // known-bad shape — hand off, then advance
        }
        return IngestDecision.SKIP;            // log and move on
    }
}
```

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
- Poison handling is skip-or-seek (§5); bounded-retry and dead-letter are future (#108 / #111).
- The Kafka transport edges are not yet reflected in `topology.json`, so the MCP
  `trace_event_flow` tool can't confirm a Kafka end-to-end path (see #312) — verify
  the generated `KafkaTransportBootstrap` directly meanwhile.

## Beyond

See the full worked service in
[`tiko-examples/08_kafka_order_warehouse`](../../tiko-examples/08_kafka_order_warehouse),
and [`persistence.md`](./persistence.md) for the `@Produces DataSource` pattern these
handlers typically write to.
