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

## 5. Poison messages (undeserializable records)

> **⚠️ Maintainer-verify (ties to #313):** on a deserialization failure the consumer
> runner currently seeks back to the same offset and retries, with no first-class
> skip / dead-letter hook (#313). Until that lands, an undeserializable record can
> stall its partition. The interim pattern is a serializer that **never throws** —
> it logs and returns a null/sentinel that the bridge treats as "skip" — so the
> offset advances. Document the recommended pattern here once #313 is resolved.

## Trade-offs (MVP)

- Per-record commit only (`commitMode = PER_RECORD`).
- Poison-record skip/DLT is not yet first-class (see §5 / #313).
- The Kafka transport edges are not yet reflected in `topology.json`, so the MCP
  `trace_event_flow` tool can't confirm a Kafka end-to-end path (see #312) — verify
  the generated `KafkaTransportBootstrap` directly meanwhile.

## Beyond

See the full worked service in
[`tiko-examples/08_kafka_order_warehouse`](../../tiko-examples/08_kafka_order_warehouse),
and [`persistence.md`](./persistence.md) for the `@Produces DataSource` pattern these
handlers typically write to.
