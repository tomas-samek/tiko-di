# tiko-build reference — Kafka transport

> Read this when: consuming/producing Kafka, or writing the Kafka integration test.

## Kafka transport: write this shape first

The obvious instinct — a `void` method that consumes a record and calls a
service — **fails the processor**. A `@KafkaSource` is a *bridge into the local
event bus*, not a consumer: it has three hard compile-time rules.

1. Enclosing class is `@Component(scope = Scope.SINGLETON)`.
2. The method is **non-void** — it returns the local event payload (or `null` to consume the
   record without publishing anything).
3. It carries a sibling **`@EventTrigger`** on the same method.

The return value is published on the local bus **by its type**; an ordinary
`@EventHandler` for that type does the work. This is the canonical inbound
shape (verbatim from `tiko-examples/08_kafka_order_warehouse`):

```java
@Component(scope = Scope.SINGLETON)
public class OrderKafkaConsumer {

    @KafkaSource(topic = "orders")
    @EventTrigger(eventName = "OrderPlaced")   // sibling trigger is required
    public OrderPlaced fromKafka(OrderPlaced payload) {
        return payload;                        // non-void: becomes the local event
    }
}

@Component(scope = Scope.SINGLETON)
public class WarehouseService {
    @EventHandler
    public void on(OrderPlaced event) { /* dispatched by type; a failure here is not redelivered (below) */ }
}
```

Outbound is the mirror: a `@KafkaSink` is subscribed by the runtime to its
parameter type — when that event is published locally, the return value is
serialized to the topic. Same `SINGLETON` rule; **do not** also add
`@EventHandler` (that double-fires). `partitionKey` names an accessor on the
return type for the message key.

```java
@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {

    @KafkaSink(topic = "orders", partitionKey = "orderId")
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
```

Broker config binds to `tiko.kafka.*` with **kebab-case** keys
(`bootstrap-servers`, not `bootstrapServers`) — they are `@Key`-declared;
see the key table in [`reference/api-signatures.md`](api-signatures.md). Full contract, configuration, and
the poison-record story: [`docs/cookbooks/kafka.md`](https://github.com/tomas-samek/tiko-di/blob/main/docs/cookbooks/kafka.md).

### The offset commits when the event reaches the bus, not when handlers succeed

The bridge and the handlers of its event look like one flow, but only the bridge side is
redelivered. Deserialize, the bridge method and the local publish fail onto the ingest-error
path below (`SEEK` by default: redelivered). A sync `@EventHandler` that throws goes to the
`ErrorHandler`, and the offset is committed anyway; the commit doesn't wait for an async handler.
So moving a write from the bridge into a handler changes what happens when the write fails.

Work that must be redelivered when it fails goes in the bridge method, before it returns:
the write itself, or an outbox row that later work picks up. Make it idempotent, because a
redelivered record runs the bridge again. Handlers get the work whose failure may be logged
and dropped, or that you recover yourself.

But a bridge exception is retried under `SEEK` until the bridge stops throwing, so a failure
that retrying can't fix (a result for an order your database doesn't have) blocks the partition
for good. Handle the permanent cases without letting them retry: if the bridge recognizes the
case itself, log it and **return `null`** (the record is committed, nothing is published; in Kotlin
declare the return type nullable). For an exception from deeper down, or to dead-letter it, throw
a dedicated exception and decide it from a `KafkaIngestErrorDecider` (below). Everything else keeps
being sought back. Decider reference:
`OrderKafkaConsumer` + `WarehouseIngestPolicy` + `RejectedOrderDoesNotBlockPartitionIT` in
[`tiko-examples/08_kafka_order_warehouse`](https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples/08_kafka_order_warehouse).

### A record that fails ingest: the default blocks its partition, on purpose

When deserialize, the bridge method, or the local publish throws, the runner routes a
`KafkaIngestError` to the `ErrorHandler` and applies `tiko.kafka.poison-record-policy`.
The default, **`SEEK`**, seeks back and redelivers the record — pausing only that
partition with a doubling backoff (`seek-backoff`, default `PT0.5S`, capped at
`seek-backoff-max`, default `PT30S`). A genuinely bad record therefore **blocks its
partition** until it is removed or the consumer is reconfigured; other partitions keep
flowing.

That is deliberate, not an oversight. In general a failure can be transient, and
deserialization is not exempt: a schema-registry deserializer makes a network call, so
during a rolling deploy the same good bytes fail now and succeed seconds later. `SEEK`
rides that out with no data loss. The exception is a record the serializer reports as
**undecodable**: the bundled `JsonKafkaSerializer` is local and deterministic, and every
decode failure it raises is an `io.tiko.UndecodablePayloadException` — the same bytes
fail on every redelivery, so retrying them only stalls the partition.
**`SKIP`** commits past every failed record — including ones that failed for a transient
reason — so set it only on a stream where losing a record on a blip is acceptable. It is
not the way to get "retry, then give up".

**For bounded retry, then dead-letter, register a `KafkaIngestErrorDecider`** — at most
one, as a SINGLETON component. When present it replaces the static policy for every
`@KafkaSource` topic (branch on `error.topic()` if topics differ):

```java
import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaIngestErrorDecider;

@Component(scope = Scope.SINGLETON)
public class OrdersIngestPolicy implements KafkaIngestErrorDecider {
    @Override
    public IngestDecision decide(KafkaIngestError error, int attempt) {
        // attempt = consecutive failures of this record, starting at 1.
        // SEEK keeps the seek-backoff pause, so 5 attempts ≈ 7.5 s with the defaults.
        return attempt < 5 ? IngestDecision.SEEK : IngestDecision.DEAD_LETTER;
    }
}
```

**Skip undecodable records, retry everything else, then dead-letter** — branch on the
typed signal, never on message text or stack frames:

```java
import io.tiko.UndecodablePayloadException;

@Component(scope = Scope.SINGLETON)
public class SkipUndecodablePolicy implements KafkaIngestErrorDecider {
    @Override
    public IngestDecision decide(KafkaIngestError error, int attempt) {
        if (error.cause() instanceof UndecodablePayloadException) {
            return IngestDecision.DEAD_LETTER; // can never decode: hand off now, no retry
        }
        return attempt < 5 ? IngestDecision.SEEK : IngestDecision.DEAD_LETTER;
    }
}
```

Return `SKIP` instead of `DEAD_LETTER` on the first branch to drop such records with
only the logged `KafkaIngestError`. A custom serializer gets the same treatment by
throwing `UndecodablePayloadException` for input it can never decode (and any other
exception for a failure that may pass on retry).

`DEAD_LETTER` routes a `KafkaRecordDeadLettered` (topic, partition, offset, headers,
cause, `attempts`) to the `ErrorHandler` and commits past the record. There is no
dead-letter *topic*: forward it from the handler to whatever sink you run.

```java
TikoOptions.builder().errorHandler(ctx -> {
    switch (ctx) {
        case KafkaRecordDeadLettered dl -> deadLetters.send(dl); // your sink
        default -> new DefaultErrorHandler().onError(ctx);        // keep the default log line
    }
}).build();
```

The other outcomes are `SKIP` (commit past) and `FAIL` (stop this topic's consumer). A
decider that throws or returns `null` falls back to `SEEK`.

### Testing Kafka bridges: use the fake broker, never a real one in unit/IT scope

Do NOT try to disable the transport by deleting `META-INF/services` files, hiding the SPI
with classloader tricks, or hand-rebuilding `KafkaBootstrapSupport`. The supported seam is
one option + one helper:

```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
        .testWiring(true) // a test container: apply @TestComponents too
        .configSource(ConfigSources.classpath("application.yaml"))
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) {
    broker.produce("orders", new JsonKafkaSerializer().serialize(event)); // drive @KafkaSource
    c.getEventBus().publish(outboundEvent);                               // drive @KafkaSink
    assertThat(broker.produced("notifications")).hasSize(1);
}
```

- `configSource(...)` is still required if the app declares any `@Configuration`
  (including `tiko-kafka`'s own) — set it exactly like the app's `Main` does, or
  `Tiko.create` fails config validation before the transport substitution runs.

Inbound consumption is asynchronous (background poll thread): assert with Awaitility
(`await().atMost(...)`), never `Thread.sleep`. Reference ITs:
`tiko-examples/08_kafka_order_warehouse/*/src/test/java/.../FakeBroker*IT.java`.

**If your module builds a shaded jar:** keep `ServicesResourceTransformer` in the
`maven-shade-plugin` transformers. Tiko finds the Kafka transport, every module's
`@Configuration` binders and every module's container through `META-INF/services/` files,
which that transformer merges;
without it the fat jar keeps one module's copy and startup fails on the other module's keys.
Also, failsafe defaults to running ITs against the packaged fat jar, which puts bundled
dependency classes on the classpath twice; add
`<classesDirectory>${project.build.outputDirectory}</classesDirectory>` to the
`maven-failsafe-plugin` configuration. The poms under
`tiko-examples/08_kafka_order_warehouse/*/pom.xml` show both blocks.
