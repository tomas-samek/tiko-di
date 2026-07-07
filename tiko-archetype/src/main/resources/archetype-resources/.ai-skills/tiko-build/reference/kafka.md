# tiko-build reference — Kafka transport

> Read this when: consuming/producing Kafka, or writing the Kafka integration test.

## Kafka transport: write this shape first

The obvious instinct — a `void` method that consumes a record and calls a
service — **fails the processor**. A `@KafkaSource` is a *bridge into the local
event bus*, not a consumer: it has three hard compile-time rules.

1. Enclosing class is `@Component(scope = Scope.SINGLETON)`.
2. The method is **non-void** — it returns the local event payload.
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
    public void on(OrderPlaced event) { /* the actual work — dispatched by type */ }
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

### Testing Kafka bridges: use the fake broker, never a real one in unit/IT scope

Do NOT try to disable the transport by deleting `META-INF/services` files, hiding the SPI
with classloader tricks, or hand-rebuilding `KafkaBootstrapSupport`. The supported seam is
one option + one helper:

```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
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

**If your module builds a shaded jar:** failsafe defaults to running ITs against the
packaged fat jar, which duplicates bundled dependency classes on the classpath and fails
container boot with `duplicate @Configuration prefix 'tiko.kafka'`. Add
`<classesDirectory>${project.build.outputDirectory}</classesDirectory>` to the
`maven-failsafe-plugin` configuration — see the poms under
`tiko-examples/08_kafka_order_warehouse/*/pom.xml` for the exact block.
