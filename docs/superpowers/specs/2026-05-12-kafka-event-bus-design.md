# Kafka event bus — design

> Status: draft, awaiting approval.
> Date: 2026-05-12.
> Related: [docs/VISION.md](../../VISION.md) (event pipeline trade-offs), [docs/events.md](../../events.md) (current event model). New GitHub issue to be filed alongside this spec.

## Goals

Bring Kafka into Tiko as the first concrete instance of a **universal transport-adapter pattern**, with the constraint that the existing local `EventBus` / `@EventHandler` / `@EventTrigger` contract stays untouched and the pattern generalises to every later transport (HTTP, scheduler, file, gRPC, …) without further architectural decisions.

Concretely:

- A handler annotated `@EventHandler(OrderPlaced)` runs whether the event was published in-process or arrived from Kafka. Same code, same imports, same tests.
- The Kafka module is fully self-contained: its own annotations, its own annotation processor, its own runtime types. `tiko-processor` learns nothing about Kafka; every future transport ships as a sibling module with no edits to anything that exists today.
- The MVP is a runnable PoC (demo + tests) that proves the architecture across two separate JVMs. Production-grade features (Avro / schema registry / batch commits / DLQs / exactly-once / transactional producers) are *out of MVP scope but explicit future extension points*, each with the seam already in MVP so future work is additive.

## Non-goals

- Avro and schema-registry support. Ship JSON-only in MVP; design the serializer SPI so a future `tiko-kafka-avro` plugs in cleanly. Undetermined future milestone.
- Batch commit modes (`CommitMode.BATCH`, `AT_MOST_ONCE`, `MANUAL`). MVP ships `PER_RECORD` only; the enum and the internal `CommitStrategy` interface establish the seam.
- Shared consumer pool across topics. MVP is thread-per-topic; `KafkaConsumerRunner` is a sealed interface so a future shared-pool runner is additive.
- Pluggable partition-key extractors. MVP accepts a string component-accessor name in `@KafkaSink(partitionKey = "...")`; future could resolve a named `KafkaPartitionKeyExtractor<T>` bean.
- ResponseSync (compile-time-wired request/reply). Belongs to the HTTP-transport era; `TransportBootstrap` SPI doesn't preclude it (a later revision adds a reply channel).
- Per-source dead-letter handling.
- Exactly-once / transactional producers. Already covered by the existing `producer-properties` pass-through plus a future `@KafkaSink(transactional = true)`.
- Cross-process orchestration that isn't Kafka (use a service mesh, per VISION).

## Architecture overview

Two new Maven modules:

- **`tiko-kafka`** — annotations (`@KafkaSource`, `@KafkaSink`), runtime types (`KafkaContext`, `KafkaSerializer<T>`, `NamedKafkaSerializer`, `KafkaConfig`), the Apache Kafka client wrapper, the consumer runner, the `FakeKafkaBroker` test helper, and the generated `KafkaTransportBootstrap`. Compile + runtime dependency.
- **`tiko-kafka-processor`** — its own annotation-processor jar with its own `META-INF/services/javax.annotation.processing.Processor` entry. Knows nothing about `tiko-processor`. Users add it to `<annotationProcessorPaths>` alongside `tiko-processor`.

One small SPI added to **`tiko-api`**:

```java
package io.tiko;

public interface TransportBootstrap {
    /**
     * Called once after {@code container.start()} returns. The transport may subscribe
     * to the EventBus, launch consumer threads, and resolve bridge components via
     * {@link Container#get(Class)}. Bridge components are guaranteed to be instantiated
     * by the time this method is called.
     */
    void start(Container container);

    /**
     * Called once during {@code container.shutdown()}, BEFORE the container runs its
     * own {@code @PreDestroy} LIFO chain. The transport must release its resources
     * (close consumers/producers, join threads) so its bridge {@code @Component}s are
     * still alive when releases happen.
     */
    void shutdown();
}
```

`Tiko.createInternal(...)` discovers all `TransportBootstrap` impls via `ServiceLoader.load(TransportBootstrap.class)`. `tiko-kafka-processor` emits one `io.tiko.generated.KafkaTransportBootstrap implements TransportBootstrap` per compilation unit, registered via a generated `META-INF/services/io.tiko.TransportBootstrap` entry.

Module dependency diagram:

```
tiko-api  (adds TransportBootstrap SPI; widens ErrorContext)
    ↑
tiko-processor                       (unchanged — no Kafka awareness)
    ↑
tiko-runtime                          (discovers TransportBootstrap via ServiceLoader)
    ↑
tiko-kafka                            ← new (annotations, runtime, client wrapper, fake)
    ↑
tiko-kafka-processor                  ← new (its own AP jar)
```

## Public API

### `tiko-kafka` annotations

```java
package io.tiko.kafka;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface KafkaSource {
    String topic();
    String consumerGroup() default "";                                  // empty → KafkaConfig.consumerGroup
    Class<? extends KafkaSerializer<?>> serializer()
            default KafkaSerializer.Default.class;                      // marker → KafkaConfig.serializer
    CommitMode commitMode() default CommitMode.PER_RECORD;              // future: BATCH, AT_MOST_ONCE, MANUAL
}

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface KafkaSink {
    String topic();
    String partitionKey() default "";                                   // record-component accessor on return type; empty → null key
    Class<? extends KafkaSerializer<?>> serializer()
            default KafkaSerializer.Default.class;
}

public enum CommitMode { PER_RECORD /* future: BATCH, AT_MOST_ONCE, MANUAL */ }
```

### `tiko-kafka` runtime types

```java
package io.tiko.kafka;

/** Transport-specific metadata; injected as an optional 2nd parameter on bridge methods. */
public record KafkaContext(
        String topic,
        int partition,
        long offset,
        Instant timestamp,
        Headers headers) {}

/** Serializer SPI. MVP ships JsonKafkaSerializer; future modules ship Avro et al. */
public interface KafkaSerializer<T> {
    byte[] serialize(T value);
    T deserialize(byte[] bytes, Class<T> type);

    /** Marker type meaning "use whatever the global YAML default is." */
    final class Default implements KafkaSerializer<Object> {
        private Default() { throw new UnsupportedOperationException("marker only"); }
        @Override public byte[] serialize(Object value)                  { throw new UnsupportedOperationException(); }
        @Override public Object deserialize(byte[] b, Class<Object> t)   { throw new UnsupportedOperationException(); }
    }
}

/** Auxiliary SPI used by the runtime to bind a name from YAML to a KafkaSerializer impl. */
public interface NamedKafkaSerializer {
    String name();                          // e.g. "json"
    KafkaSerializer<?> serializer();
}

@Configuration(prefix = "tiko.kafka")
public record KafkaConfig(
        @Default("localhost:9092") String bootstrapServers,
        @Default("tiko-app")       String consumerGroup,
        @Default("json")           String serializer,
        @Default("earliest")       String autoOffsetReset,
        @Default("PT0.5S")         Duration pollTimeout,
        Map<String, String> producerProperties,    // pass-through to Kafka client
        Map<String, String> consumerProperties     // pass-through to Kafka client
        ) {}
```

### Bridge method shapes

Inbound:

```java
@Component
public class OrderKafkaConsumer {
    @KafkaSource(topic = "orders")
    @EventTrigger(eventName = "OrderPlaced")
    public OrderPlaced fromKafka(OrderPlaced payload) {              // pass-through; framework owns deserialization
        return payload;
    }

    // Optional KafkaContext second parameter
    @KafkaSource(topic = "audits")
    @EventTrigger(eventName = "AuditRecorded")
    public AuditRecorded fromKafkaWithCtx(AuditPayload payload, KafkaContext ctx) {
        return new AuditRecorded(payload.id(), payload.action(), ctx.headers().lastHeader("X-Correlation-Id"));
    }
}
```

Outbound:

```java
@Component
public class OrderKafkaPublisher {
    @EventHandler
    @KafkaSink(topic = "orders", partitionKey = "orderId")
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;                                                 // pass-through; framework owns serialization
    }
}
```

Handler shape is unchanged from today:

```java
@Component
public class WarehouseService {
    @EventHandler
    public void on(OrderPlaced event) {                               // no Kafka import in this file
        // ...
    }
}
```

## Compile-time validation (`tiko-kafka-processor`)

Each rule is a hard error at `javac` time with a message naming the file/line and a suggested fix.

1. `@KafkaSource` requires a sibling `@EventTrigger` on the same method (else the message has nowhere to go).
2. `@KafkaSink` requires a sibling `@EventHandler` on the same method.
3. The enclosing `@Component` must be `Scope.SINGLETON`. Kafka consumer threads (inbound) and EventBus subscriptions (outbound) run outside any request/event scope; a `REQUEST`/`EVENT`/`PROTOTYPE` bridge cannot be resolved.
4. Outbound bridge: return type must be non-void. The serializer must be able to handle the return type (checked structurally for the JSON case — a `record` or POJO; a default annotation-hint method on `KafkaSerializer` will be defined when Avro lands).
5. Optional `KafkaContext` parameter, if present, must be the second parameter and typed exactly `KafkaContext` (no subtype).
6. `@KafkaSink(partitionKey = "name")`: the name must resolve to a record-accessor method on the return type. Empty → null key (no validation).
7. `@KafkaSource` / `@KafkaSink` cannot coexist on the same method.
8. `serializer = ConcreteImpl.class`: the named impl must implement `KafkaSerializer<? super T>` for the bridge's payload type T.

## Generated code

`tiko-kafka-processor` emits exactly two classes per module:

- **`io.tiko.generated.KafkaTransportBootstrap implements io.tiko.TransportBootstrap`** — discovered via `ServiceLoader`. Contains the static lists of `KafkaSourceDescriptor` and `KafkaSinkDescriptor` records (one per annotated bridge method) and the dispatcher static helpers. No reflection at runtime — every bridge invocation is a direct method call on the resolved component instance.
- **`META-INF/services/io.tiko.TransportBootstrap`** with the FQN of the generated bootstrap.

Generated dispatcher shape (one static helper per bridge method, illustrative):

```java
// Inbound — generated, per @KafkaSource method
private static Object DISPATCH_SOURCE_0(Container container, byte[] bytes, KafkaContext ctx) {
    OrderKafkaConsumer bridge = container.get(OrderKafkaConsumer.class);
    OrderPlaced payload = (OrderPlaced) SERIALIZER_0.deserialize(bytes, OrderPlaced.class);
    return bridge.fromKafka(payload);              // or bridge.fromKafkaWithCtx(payload, ctx)
}

// Outbound — generated, per @KafkaSink method
private static ProducerRecord<String, byte[]> DISPATCH_SINK_0(Container container, Object event) {
    OrderKafkaPublisher bridge = container.get(OrderKafkaPublisher.class);
    OrderPlaced result = bridge.toKafka((OrderPlaced) event);
    byte[] bytes = SERIALIZER_0.serialize(result);
    String key = result.orderId();                 // resolved from @KafkaSink(partitionKey="orderId")
    return new ProducerRecord<>("orders", key, bytes);
}
```

## Runtime lifecycle

### Startup sequence

1. `Tiko.create(options)` → builds the (single- or multi-module) container and calls `container.start()` (existing).
2. After `start()` returns, `Tiko.createInternal(...)` calls `ServiceLoader.load(TransportBootstrap.class)` and invokes `.start(container)` on each impl.
3. `KafkaTransportBootstrap.start(container)`:
   - Resolves bound `KafkaConfig` via `container.get(KafkaConfig.class)` (auto-bound by the existing `tiko-config` plumbing; no special-casing).
   - Resolves the named serializer for each source/sink (via `ServiceLoader.load(NamedKafkaSerializer.class)`); fails fast if a name is unknown.
   - For each `@KafkaSink` descriptor: subscribes a callback on the EventBus (`eventBus.subscribe(eventType, callback)`) that calls the generated sink dispatcher and ships the resulting `ProducerRecord` via the shared `KafkaProducerClient`.
   - For each `@KafkaSource` descriptor: instantiates a `KafkaConsumerRunner` (sealed interface; MVP impl is `ThreadPerTopicRunner`) and starts its daemon thread named `tiko-kafka-consumer-{topic}`.
4. Container is fully wired. Subsequent `container.events().publish(...)` calls reach any matching sinks; consumed Kafka records reach matching local handlers.

### Inbound consume loop (per source thread)

```java
while (running.get()) {
    var records = consumer.poll(config.pollTimeout());
    for (var record : records) {
        var tp = new TopicPartition(record.topic(), record.partition());
        try {
            var ctx = new KafkaContext(
                    record.topic(), record.partition(), record.offset(),
                    Instant.ofEpochMilli(record.timestamp()), record.headers());
            Object event = dispatchSource(record.value(), ctx);     // generated dispatcher
            eventBus.publish(event);                                 // local handlers fire
            consumer.commitSync(Map.of(tp,
                    new OffsetAndMetadata(record.offset() + 1)));
        } catch (Exception ex) {
            errorHandler.handle(new KafkaIngestError(
                    record.topic(), record.partition(), record.offset(),
                    record.headers(), ex));
            consumer.seek(tp, record.offset());
            break;   // re-poll on next iteration
        }
    }
}
```

`commitSync(offset+1)` is per-record. Slow at extreme throughput; backs the at-least-once contract documented in [docs/events.md](../../events.md). Bridge throw ⇒ seek-back ⇒ same record redelivered on next poll. Handlers must be idempotent (this is already the documented contract).

**Trigger semantics on bridge methods — MVP scope cut.** The existing `@EventTrigger` spread / guard / async machinery lives inside `EventRegistryGenerator`'s generated per-handler dispatchers. Reusing it from `tiko-kafka-processor` would either duplicate that logic (drift risk) or require factoring it into a shared helper in `tiko-runtime`. MVP cuts cleanly: the Kafka dispatcher does `eventBus.publish(returnValue)`, and the event is routed to local handlers by its runtime class. The `eventName` argument on a bridge's `@EventTrigger` is treated as **tracing metadata** (surfaced in `Event<?>` origin chains and error contexts) but does not drive dispatch. Spread / guards / async on bridge `@EventTrigger` are explicit follow-up work — see Risks.

### Outbound dispatch

The EventBus subscription registered for each `@KafkaSink` runs **synchronously** on the publishing thread by default — same as any other local handler. Local `@EventHandler` subscribers always run first (registration order); the Kafka send is the last subscriber added per event type. A send failure surfaces as `KafkaEgressError` via the configured `ErrorHandler`; it never blocks local handlers (which have already run) and never blocks the publisher thread beyond the `producer.send` call.

For applications that want non-blocking sends, the user annotates the sink method `@EventHandler(async = true)` exactly as they do today for any other slow handler — no Kafka-specific knob.

### Shutdown sequence

1. `container.shutdown()` enters.
2. Container publishes `ApplicationEndingEvent` (existing).
3. Container iterates registered `TransportBootstrap` impls and calls `.shutdown()` on each. For Kafka: sets `running.set(false)`, calls `consumer.wakeup()` on each runner, joins threads with a configurable timeout (folds into #48), closes producer.
4. Container's own `@PreDestroy` LIFO chain runs (existing). Bridge `@Component`s are torn down *after* the Kafka clients release them, avoiding "called close() after PreDestroy" races.

## Serialization

Resolution order per source/sink:

1. Annotation parameter set to a concrete class other than `KafkaSerializer.Default` → use that impl. Looked up by class.
2. Otherwise: the serializer named by `KafkaConfig.serializer` (default `"json"`). Looked up via `ServiceLoader<NamedKafkaSerializer>` by name.
3. Unknown name at startup → container fails fast with a message naming the missing serializer and the YAML key.

MVP ships exactly one `NamedKafkaSerializer` impl: `JsonKafkaSerializer` (Jackson under the hood; deps shadow-bundled in `tiko-kafka` to avoid version conflicts with whatever the user pulls in).

Custom serializers register themselves the same way (`META-INF/services/io.tiko.kafka.NamedKafkaSerializer`). Future `tiko-kafka-avro` registers `"avro"`.

## Configuration

`tiko-kafka` ships its own `@Configuration` record (`KafkaConfig`, above). Auto-discovered through the existing `tiko-config` plumbing — `KafkaConfig` is registered in `tiko-kafka`'s `META-INF/tiko/configs.txt`, bound at startup the same way any other `@Configuration` record is. No special path through `Tiko.createInternal`.

`producer-properties` / `consumer-properties` are pass-through maps into the underlying Kafka client `Properties`. Every native client knob (`linger.ms`, `compression.type`, `max.poll.records`, `enable.idempotence`, etc.) is reachable without the framework wrapping each one. Tiko-supplied values (`bootstrap.servers`, `group.id`, `auto.offset.reset`, `key.deserializer`, `value.deserializer`) win on collision and are documented in the module README.

`META-INF/tiko/defaults.yaml` baked into `tiko-kafka.jar` carries the framework defaults so users with sensible setups need no `application.yaml` at all.

## Error handling

Two new permits widen the sealed `ErrorContext` in `tiko-api` (folds into the planned #52 work):

```java
public sealed interface ErrorContext
        permits EventHandlerError, KafkaIngestError, KafkaEgressError {
    Throwable cause();
}

public record KafkaIngestError(
        String topic, int partition, long offset, Headers headers, Throwable cause)
        implements ErrorContext {}

public record KafkaEgressError(
        String topic, Object event, Throwable cause)
        implements ErrorContext {}
```

Routing identical to today's `EventHandlerError`: default `ErrorHandler` logs WARN via `java.util.logging`; users override via `TikoOptions.errorHandler(...)` and pattern-match on context type.

Behaviour on throw:

- **Ingest** — consumer `seek` back to the failed offset; loop re-polls. Same record redelivered. Backs the documented at-least-once + idempotent-handlers contract.
- **Egress** — error context dispatched; message NOT sent; sibling subscribers (other `@KafkaSink` for the same event type) still run. Local handlers always run before any `@KafkaSink` callback.

## Demo — `tiko-examples/07_kafka_order_warehouse`

Each service is its own Maven module with its own `main()` — fully runnable as a separate JVM today, dockerizable later with no source change.

```
07_kafka_order_warehouse/
├── README.md                ← side-by-side: local @EventHandler vs Kafka-fed handler look identical
├── pom.xml                  ← parent
├── docker-compose.yml       ← Kafka now; adds service image entries once they exist
├── shared-events/
│   └── OrderPlaced.java
├── order-service/
│   ├── pom.xml              ← shade plugin → order-service.jar
│   ├── src/main/java/.../Main.java                  ← Tiko.create(...) + small CLI loop to place orders
│   ├── src/main/java/.../OrderService.java
│   ├── src/main/java/.../OrderKafkaPublisher.java   ← @EventHandler + @KafkaSink
│   └── src/main/resources/application.yaml
├── warehouse-service/
│   ├── pom.xml              ← shade plugin → warehouse-service.jar
│   ├── src/main/java/.../Main.java                  ← Tiko.create(...) + blocks until SIGTERM
│   ├── src/main/java/.../WarehouseService.java      ← @EventHandler — no Kafka import
│   ├── src/main/java/.../OrderKafkaConsumer.java    ← @KafkaSource + @EventTrigger
│   └── src/main/resources/application.yaml
└── e2e/
    └── src/test/java/.../OrderToWarehouseE2ETest.java
```

**Local manual run** (instructions in `README.md`):

```bash
docker compose -f tiko-examples/07_kafka_order_warehouse/docker-compose.yml up -d kafka
mvn -pl tiko-examples/07_kafka_order_warehouse/warehouse-service exec:java &
mvn -pl tiko-examples/07_kafka_order_warehouse/order-service exec:java
# in order-service CLI, place an order → watch warehouse-service log
```

**Future** (once images exist): `docker compose up` brings up Kafka + both services. Source unchanged.

Companion to existing `tiko-examples/05_multi_module` (which aggregates two modules in *one* JVM via `AggregatingContainer`). README of `07` notes the relationship: same domain split, opposite deployment topology, both supported.

## Testing

Three layers:

**Layer 1 — unit (`tiko-kafka/src/test/java/`, no Docker, sub-second feedback)**

Producer/consumer access goes through small interfaces inside `tiko-kafka`:

```java
public interface KafkaProducerClient {
    void send(ProducerRecord<String, byte[]> record);
    void close();
}

public interface KafkaConsumerClient {
    ConsumerRecords<String, byte[]> poll(Duration timeout);
    void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets);
    void seek(TopicPartition tp, long offset);
    void wakeup();
    void close();
}
```

Production: `ApacheKafkaProducerClient` / `ApacheKafkaConsumerClient` wrap real Apache clients. Tests: `FakeKafkaBroker` provides in-memory impls plus a `produce(topic, payload, headers)` helper to inject records and a `produced(topic)` accessor to read what was sent. `KafkaTransportBootstrap` takes the client factories via constructor, defaulting to the Apache impls; tests inject the fake.

Covers: bootstrap registration, bridge dispatch (1-arg vs 2-arg KafkaContext), serializer resolution, commit-on-success, seek-on-failure, error-context routing, sink subscription ordering.

**Layer 2 — integration (`tiko-kafka/src/integTest/java/`, Docker required)**

`org.testcontainers:kafka` boots a real broker. Covers: real client compat, partition assignment, consumer rebalancing on shutdown, real-network commit semantics, real `seek` behaviour. Lives in `integTest/` so default `mvn test` stays Docker-free; CI runs `mvn verify`.

**Layer 3 — example end-to-end (`tiko-examples/07_kafka_order_warehouse/e2e/`)**

Process-orchestrated to actually prove cross-JVM separation:

- Testcontainers boots Kafka.
- Test uses `ProcessBuilder` to fork each service's shaded JAR (built by the reactor before `e2e/` runs) as a separate JVM, wired to the Testcontainers broker via env vars.
- Probe mechanism: warehouse-service writes a probe line to `${probe.file}` (configurable via YAML `${VAR}` interpolation) whenever it handles an `OrderPlaced`. E2E test tails this file with a timeout.
- Clean shutdown via `Process.destroy()`; both services exit cleanly via the existing `Container.close()` AutoCloseable path.

Slower (cold-start ×2 per run) but proves what the demo claims to prove — that the two halves are decoupled processes glued only by Kafka.

## MVP scope — in / out

**In:**

- `@KafkaSource`, `@KafkaSink` annotations.
- `KafkaContext` record, `KafkaSerializer<T>` SPI, `NamedKafkaSerializer` SPI, `JsonKafkaSerializer` impl.
- `TransportBootstrap` interface in `tiko-api` + `ServiceLoader` discovery wired into `Tiko.createInternal`.
- `tiko-kafka-processor` with the compile-time validations listed above.
- `KafkaConfig` `@Configuration` record (auto-discovered via existing `tiko-config` plumbing).
- `KafkaIngestError` + `KafkaEgressError` permits on `ErrorContext`.
- Thread-per-topic consumer, per-record commit, seek-back on bridge throw.
- `KafkaProducerClient` / `KafkaConsumerClient` interfaces + Apache-backed prod impls + `FakeKafkaBroker` test impl.
- `tiko-examples/07_kafka_order_warehouse` end-to-end demo with the three-layer test setup.

**Out (each with the seam already in MVP so the work is additive):**

- **Full `@EventTrigger` semantics on bridge methods** — MVP publishes the bridge's return value via `eventBus.publish(...)` (dispatched by runtime class). Spread / guards / async are honoured today on `@EventHandler @EventTrigger` chains but not on `@KafkaSource @EventTrigger`. Follow-up factors the trigger-dispatch logic out of `EventRegistryGenerator` into a shared `tiko-runtime` helper that both processors call. No annotation or public-API change.
- **Avro + schema registry** — future `tiko-kafka-avro` module; plugs in via `NamedKafkaSerializer`. New issue to file.
- **Batch commit modes** — `CommitMode` enum has only `PER_RECORD`; future adds `BATCH`, `AT_MOST_ONCE`, `MANUAL`. New issue.
- **Topic/queue semantics** — `@KafkaSource(consumerGroup = "...")` parameter exists from MVP; runtime honours it. Not exercised by the demo. New issue when a use case surfaces.
- **Shared consumer pool** — internal `KafkaConsumerRunner` is a sealed interface; future shared-pool runner is a new permit. No annotation change.
- **Pluggable partition-key extractor** — MVP `partitionKey = "componentAccessor"`; future resolves a named `KafkaPartitionKeyExtractor<T>` bean. No annotation rename.
- **ResponseSync** (compile-time-wired request/reply) — separate design when HTTP transport begins; `TransportBootstrap` SPI doesn't preclude it.
- **Per-source DLQ** — future `@KafkaSink(deadLetterTopic = "...")` parameter. No structural change.
- **Exactly-once / transactional producers** — covered by the existing `producer-properties` pass-through plus a future `@KafkaSink(transactional = true)`.
- **Cross-transport `Picker`** — discovering `@KafkaSource`-fed event types programmatically; rides on existing `Container.getAll(...)` if it ever matters.

## Future extension points (recap)

Each is named in the spec so that a reviewer can verify the MVP doesn't accidentally close a door:

| Future capability                       | Seam in MVP                                                                |
|-----------------------------------------|----------------------------------------------------------------------------|
| Full `@EventTrigger` on bridges         | Factor trigger dispatcher out of `EventRegistryGenerator`; share via tiko-runtime |
| Avro / schema registry                  | `NamedKafkaSerializer` ServiceLoader SPI; `KafkaSerializer<T>` interface   |
| Batch / at-most-once commit             | `CommitMode` enum; internal `CommitStrategy` interface                     |
| Topic/queue patterns                    | `@KafkaSource(consumerGroup = "...")` parameter; runtime honours it        |
| Shared consumer pool                    | `KafkaConsumerRunner` sealed interface                                     |
| Pluggable partition-key                 | `@KafkaSink(partitionKey = "...")` is already an extensible string         |
| ResponseSync                            | `TransportBootstrap` SPI doesn't preclude a reply channel                  |
| Per-source DLQ                          | `@KafkaSink` annotation accepts further parameters                         |
| Transactional / EOS                     | `producer-properties` pass-through; future `transactional = true`          |
| Other transports (HTTP, scheduler)      | Identical recipe — `@*Source`/`@*Sink` + `TransportBootstrap` impl         |

## Risks & open questions

- **Trigger-dispatch factoring.** Honouring full `@EventTrigger` semantics (spread / guards / async) on bridge methods requires the trigger-dispatch logic currently inlined in `EventRegistryGenerator` to be factored into a reusable helper in `tiko-runtime`. MVP avoids the refactor by treating `eventName` as tracing metadata on bridges and publishing the return value directly. When this is unblocked, both `EventRegistryGenerator` and `KafkaTransportBootstrap` call the shared helper — no change to public API.
- **JSON via Jackson + shadow-bundling.** Bundling Jackson inside `tiko-kafka.jar` (under a relocated package) keeps Tiko's "no transitive deps in user-facing artifacts" stance, but adds ~2 MB to the jar and a maven-shade step to the build. The alternative — declaring a Jackson dependency openly — exposes users to Jackson version conflicts. Decision deferred to implementation; the spec assumes shadow-bundling.
- **`Headers` type in `KafkaContext`.** Today this is `org.apache.kafka.common.header.Headers` (from the Kafka client). That couples `KafkaContext` to the Kafka client jar even for users who only declare bridge methods (compile time). Acceptable for an MVP; revisit if it surfaces as friction. Wrapping it in a Tiko-owned `MessageHeaders` type is a follow-up.
- **`tiko-config` is currently optional.** `tiko-kafka` will require it (for `KafkaConfig`). Consumers that don't otherwise need `@Configuration` will pull `tiko-config` transitively when they add `tiko-kafka`. Acceptable; documented in the module README.
- **ServiceLoader discovery in the `Tiko.create` hot path.** Adds one classpath enumeration at startup; measured cost is sub-millisecond on the bench laptop but appears in `comparisons/` numbers when Kafka is present. Re-bench when MVP lands.
- **Multi-module aggregation.** `KafkaTransportBootstrap.start(container)` receives the aggregating `Container`; bridge component resolution and `EventBus` access go through the aggregator. Tested in `tiko-examples/07_kafka_order_warehouse` only indirectly (each service is single-module). A multi-module-plus-Kafka regression test under `tiko-examples/05_multi_module` is a follow-up.
