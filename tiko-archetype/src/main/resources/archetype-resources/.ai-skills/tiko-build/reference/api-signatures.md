# tiko-build reference — API signature sheet

> Read this when: writing any import, or unsure of a signature, annotation attribute, or config key.

## API signature sheet — exact imports and signatures

Transcribed from source. Import from this table — never from memory.

### Exact packages

| Type | Package |
|---|---|
| `@Component` `@Inject` `@Named` `@Pick` `@Produces` `@PostConstruct` `@PreDestroy` `@EventHandler` `@EventTrigger` `@EventTriggers` `@Configuration` `@Default` `@Key` `BackoffStrategy` | `io.tiko.annotations` |
| `@KafkaSource` `@KafkaSink` | `io.tiko.kafka.annotations` — **NOT** `io.tiko.annotations` |
| `Container` `EventBus` `EventCallback` `Subscription` `Scope` `Provider` `TransportBootstrap` `ErrorHandler` `ConfigSource` | `io.tiko` |
| `Tiko` `TikoOptions` `TikoDaemon` | `io.tiko.runtime` |
| `ConfigSources` | `io.tiko.config` |
| `KafkaTransport` `KafkaSerializer` `KafkaConfig` | `io.tiko.kafka` |
| `JsonKafkaSerializer` | `io.tiko.kafka.serializer` |
| `FakeKafkaBroker` `FakeKafkaTransport` | `io.tiko.kafka.test` |

**The rule:** a `cannot find symbol` on an import means a wrong package,
not a missing feature — check this table first, then `javap` the resolved
jar. Never conclude an annotation or class does not exist because one
import guess failed. Kafka types additionally require the `tiko-kafka`
dependency and the `tiko-kafka-processor` annotation-processor path —
both ship **commented out** in the scaffolded pom; enable them first.

### Signatures you will call

```java
// Bootstrap (io.tiko.runtime)
static Container Tiko.create()
static Container Tiko.create(TikoOptions options)
static TikoDaemon Tiko.daemon(TikoOptions options)
void TikoDaemon.awaitShutdown()

// Options (io.tiko.runtime) — all builder methods return Builder
static TikoOptions.Builder TikoOptions.builder()
Builder configSource(ConfigSource source)
Builder errorHandler(ErrorHandler handler)
<T> Builder override(Class<T> type, Supplier<? extends T> supplier)
<T extends TransportBootstrap> Builder replaceTransport(Class<T> transport, Function<T, TransportBootstrap> replacement)
TikoOptions build()

// Config sources (io.tiko.config.ConfigSources)
static ConfigSource classpath(String resourcePath)
static ConfigSource classpathAll(String resourcePath)
static ConfigSource file(Path path)
static ConfigSource fromMap(Map<String, Object> data)
static ConfigSource layered(ConfigSource... sources)

// Event bus (io.tiko.EventBus)
<T> void publish(T event)
<T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback)

// Fake broker (io.tiko.kafka.test) — in-process test seam
void FakeKafkaBroker.produce(String topic, byte[] payload, String... headerKv)
List<ProducerRecord<String, byte[]>> FakeKafkaBroker.produced(String topic)
Optional<ProducerRecord<String, byte[]>> FakeKafkaBroker.findProduced(String topic, String headerKey, String headerValue)
static FakeKafkaTransport FakeKafkaTransport.over(KafkaTransport original, FakeKafkaBroker broker)

// JSON serializer (io.tiko.kafka.serializer)
byte[] JsonKafkaSerializer.serialize(Object value)
<T> T JsonKafkaSerializer.deserialize(byte[] bytes, Class<T> type)
```

### Annotation attributes (with defaults)

```java
@Component(Scope scope = Scope.PROTOTYPE, String name = "", String[] profiles = {},
           Class<?>[] expose = {}, boolean exposeSelf = true)
@Produces(Scope scope = Scope.PROTOTYPE, String name = "", String[] profiles = {})
@EventHandler(boolean async = false, Class<?> eventType = Object.class, String timeout = "",
              int retries = 0, String backoff = "", BackoffStrategy backoffStrategy = BackoffStrategy.FIXED)
@EventTrigger(String eventName = "", boolean async = false, boolean spread = false,
              Class<? extends EventTriggerGuard>[] guard = EventTriggerGuard.AlwaysAllow.class)
@Configuration(String prefix)            // required
@Default(String value)                   // required
@Key(String value)                       // required — overrides the YAML key for one record component
@Named(String value)                     // required
@Pick(Class<?> value)                    // required
@KafkaSource(String topic,               // required
             String consumerGroup = "", Class<? extends KafkaSerializer> serializer = KafkaSerializer.Default.class,
             CommitMode commitMode = CommitMode.PER_RECORD)
@KafkaSink(String topic,                 // required
           String partitionKey = "", Class<? extends KafkaSerializer> serializer = KafkaSerializer.Default.class)
```

`timeout` / `backoff` take ISO-8601 durations (`"PT5S"`); `timeout` and
`retries` require `async = true`.

### Config keys — the two rules and the `tiko.kafka.*` table

1. **Your `@Configuration` records:** YAML keys bind to record component
   names **exactly** — camelCase as declared (`poolSize`, never
   `pool-size`). No kebab-case or snake_case aliasing, by design.
2. **`@Key("...")` overrides that** for a single component. Modules use it
   for kebab-case public keys; `tiko-kafka`'s `KafkaConfig` does exactly
   that, so the real broker keys are:

| `tiko.kafka.*` key | shipped default |
|---|---|
| `bootstrap-servers` | `localhost:9092` |
| `consumer-group` | `tiko-app` |
| `serializer` | `json` |
| `auto-offset-reset` | `earliest` |
| `poll-timeout` | `PT0.5S` |
| `shutdown-timeout` | `PT5S` |
| `producer-properties` | `{}` |
| `consumer-properties` | `{}` |
| `poison-record-policy` | `SEEK` (`SKIP` opt-in) |

Write these keys kebab-case exactly as above (they are `@Key`-declared;
`serializer` is the one plain camelCase-free field name). A key that
matches neither a component name nor a `@Key` value fails validation at
`Tiko.create(...)` with a nearest-key suggestion.
