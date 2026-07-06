# #414 — Transport substitution: drive a tiko app's Kafka transport through FakeKafkaBroker

**Date:** 2026-07-04
**Issue:** #414
**Branch:** `feat/issue-414-transport-substitution`

## Context

`FakeKafkaBroker` ships for in-process integration testing, but there is no
supported way to route a `@KafkaSource` / `@KafkaSink` application through
it. `Tiko.create()` unconditionally starts every ServiceLoader-discovered
`TransportBootstrap` against the configured real broker, and the generated
`KafkaTransportBootstrap` keeps its compile-time source/sink descriptors
private. Benchmark finding F4 (#269): every agent run burned its hardest
correction turns reverse-engineering this — hand-rebuilding
`KafkaBootstrapSupport`, deleting SPI registrations from `target/classes`,
repointing failsafe. The 2026-07-04 token/price analysis identified this as
the single largest cost lever for agent builds on tiko.

The seam already half-exists: `KafkaBootstrapSupport` has a public 5-arg
constructor taking client factories, and `FakeKafkaBroker` exposes
`producerClient()` / `consumerClient(group)`. What is missing is (a) a way
to stop the real transport from auto-starting and (b) access to the
generated descriptors.

## Decisions (from the resumed brainstorm)

1. **Substitution, not a boolean off-switch.** Instead of
   `startTransports(false)` + a separately-managed helper, the test
   *replaces* the discovered transport with a fake. One lifecycle (the
   container starts and stops the replacement through the existing
   `TransportAwareContainer` path), selective per transport, and the same
   test-affordance species as the accepted `TikoOptions.override(...)`.
   This resolves the compile-time-vs-runtime tension the design was paused
   on: the wiring still comes from compile-time descriptors; only the
   test hand-off is runtime, exactly like `override()`.
2. **Decorator function, not a plain instance.** The replacement receives
   the discovered bootstrap it replaces, so the fake reads the generated
   topology directly from it. Returning `null` drops the transport —
   disable falls out for free; no separate knob.
3. **Class-keyed, not string- or id-keyed.** The key is a marker interface
   class, matched by `instanceof` — mirrors `override(Class, instance)`
   and the `@Named` → `@Pick` precedent. End-to-end compile-time typing:
   key → decorator parameter → descriptor access. Consequence:
   **tiko-api is untouched** (no `id()` method, no `TransportId` type).

## Components

### 1. `TikoOptions.replaceTransport` (tiko-runtime)

```java
public <T extends TransportBootstrap> Builder replaceTransport(
        Class<T> transport, Function<T, TransportBootstrap> replacement)
```

- Repeatable; entries stored in registration order (`LinkedHashMap`).
- Registering the same key class twice throws `IllegalArgumentException`
  at builder time.
- Documented as a **test affordance** in the `override()` family, not a
  production configuration switch.

### 2. Substitution in `Tiko` (tiko-runtime)

In `startTransportsOrShutdown`, after ServiceLoader discovery and before
any `start()`:

- For each registered entry `(Class<T> key, Function<T, TransportBootstrap> fn)`:
  - Find discovered bootstraps with `key.isInstance(tb)`; apply `fn` to
    each match (an entry matching several bootstraps applies to each —
    documented, not special-cased).
  - Non-null result replaces the bootstrap in the start list (position
    preserved); `null` drops it.
  - **No match** → `ContainerInitializationException` following the
    Error Message Format: name the unmatched key, list the discovered
    transport classes, suggest checking the transport module is on the
    classpath.
  - `fn` throws → wrapped in `ContainerInitializationException` naming
    the key; the existing #348 teardown path is unchanged.
- `replaceTransport(TransportBootstrap.class, t -> null)` is the
  documented "disable all transports" idiom (matches everything).

### 3. `KafkaTransport` marker interface (tiko-kafka)

```java
package io.tiko.kafka;

public interface KafkaTransport extends TransportBootstrap {
    List<GeneratedSourceDescriptor> sources();
    List<GeneratedSinkDescriptor> sinks();
}
```

Lives in `io.tiko.kafka` (user-facing package); descriptor types remain in
`io.tiko.kafka.runtime`. This interface is both the substitution key and
the fake's window onto the compile-time wiring.

### 4. Generator change (tiko-kafka-processor)

`KafkaTransportBootstrapGenerator` switches the generated class's
superinterface from `TransportBootstrap` to `KafkaTransport` (which extends
it) and makes the existing private `sources()` / `sinks()` methods `public`
with `@Override`. No new information is
generated; compile-time output becomes reachable.

### 5. `FakeKafkaTransport` (tiko-kafka, `io.tiko.kafka.test`)

```java
public final class FakeKafkaTransport implements TransportBootstrap {
    public static FakeKafkaTransport over(KafkaTransport original, FakeKafkaBroker broker) { ... }
    @Override public void start(Container container) { ... } // idempotent, like the generated bootstrap
    @Override public void shutdown() { ... }
}
```

- `start(container)` builds `KafkaBootstrapSupport` via the existing
  public 5-arg constructor with `original.sources()`, `original.sinks()`,
  and the broker's client factories; `shutdown()` delegates.
- The parameter is typed `KafkaTransport` — no runtime cast, no
  `instanceof` check; a wrong argument is a compile error.
- The internal `TestKafkaBootstrap` test helper in tiko-kafka stays for
  descriptor-inline unit tests; `FakeKafkaTransport` is the shipped,
  generated-code-facing entry point.

### 6. The resulting test (issue acceptance shape)

```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) {
    broker.produce("orders", orderJson);
    assertThat(broker.produced("notifications")).hasSize(1);
}
```

## Testing

- **tiko-runtime (unit):** substitution replaces a matching bootstrap;
  `null` drops it; unmatched key fails fast with the formatted message;
  a throwing decorator is wrapped; `TransportBootstrap.class` matches all;
  duplicate key registration throws at builder time; replaced transport's
  `shutdown()` runs on container close (through `TransportAwareContainer`).
  All with hand-built `TransportBootstrap` fixtures — no Kafka dependency.
- **tiko-kafka (unit):** `FakeKafkaTransport.over(...)` wires a hand-built
  `KafkaTransport` fixture's descriptors to the broker factories; start
  idempotency; shutdown delegation.
- **tiko-kafka-processor (compile-test):** generated bootstrap implements
  `KafkaTransport`; `sources()` / `sinks()` are public `@Override`s.
- **End-to-end with real generated code:**
  `tiko-examples/08_kafka_order_warehouse` gains a `FakeKafkaBroker`-backed
  IT using exactly the acceptance-shape test above. Unlike the existing
  Testcontainers IT, it needs no Docker — it runs in every CI job and on
  the Docker-less dev machine, and doubles as the documented reference
  recipe.

## Documentation

- `docs/testing.md`: new "Faking the Kafka transport" section with the
  recipe.
- `.ai-skills/tiko-build/SKILL.md` (canonical) **and** the archetype's
  bundled copy: the recipe replaces the current silence that caused F4.
  The #408 anti-drift gate enforces the two stay identical.
- `tiko-examples/08` README note pointing at the new IT.

## Out of scope

- Real-broker integration tests (Testcontainers) — unchanged.
- Other transports (#117 RabbitMQ, #118 SPI audit) — the mechanism is
  generic (`Class<T extends TransportBootstrap>` key), but only the Kafka
  marker interface and fake ship now.
- Production (non-test) use of `replaceTransport` — documented as a test
  seam; no profile wiring, no config-file switch.
- Benchmark re-runs (#269 Sonnet/Haiku cells) — follow-up after this ships.

## Delivery

Single PR onto `main` from `feat/issue-414-transport-substitution`;
commits split by module concern (runtime substitution, kafka
interface+fake+generator, example IT, docs), conventional single-line
messages referencing #414.
