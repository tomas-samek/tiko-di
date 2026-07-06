# #414 Transport Substitution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a test route a tiko app's `@KafkaSource`/`@KafkaSink` wiring through `FakeKafkaBroker` via `TikoOptions.builder().replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))` — closing issue #414 / benchmark finding F4.

**Architecture:** `TikoOptions` gains a class-keyed, decorator-valued transport-replacement registry; `Tiko` applies it between ServiceLoader discovery and transport start (null result drops the transport). tiko-kafka ships a `KafkaTransport` marker interface (the substitution key + descriptor window) which the generated bootstrap now implements with public `sources()`/`sinks()`, plus a `FakeKafkaTransport` that wires those descriptors to the fake broker through the existing public 5-arg `KafkaBootstrapSupport` constructor. tiko-api is untouched.

**Tech Stack:** Java 21, Maven (`W:\tools\apache-maven\bin\mvn.cmd`, not on PATH), JavaPoet (`com.palantir.javapoet`), JUnit 5 + AssertJ + Awaitility, Google compile-testing.

**Spec:** `docs/superpowers/specs/2026-07-04-issue-414-transport-substitution-design.md`

## Global Constraints

- Branch: `feat/issue-414-transport-substitution` (already checked out; spec committed).
- Spotless/Palantir runs on every mvn invocation. On violation: `& W:\tools\apache-maven\bin\mvn.cmd -pl '!tiko-bom' spotless:apply` from the root, re-run.
- Never judge a build by a piped exit code: `& W:\tools\apache-maven\bin\mvn.cmd <goals> *> <log>; $LASTEXITCODE`, then grep the log for `BUILD SUCCESS`/`BUILD FAILURE`.
- Use Read/Edit/Write tools for source changes — never PowerShell redirection (UTF-8 BOM breaks javac).
- Tests: JUnit 5 + AssertJ only; new test method names camelCase; no `Thread.sleep` (use Awaitility for async waits); reset any system property a test sets.
- Compile-time error messages follow the Error Message Format: what's wrong + context + at least one suggested fix.
- Commits: single-line conventional `type(scope): subject (#414)` — no body, no Co-Authored-By.
- `replaceTransport` is documented as a **test affordance** in the `override()` family everywhere it is described.
- Testcontainers ITs skip on this machine (no Docker for the test JVM); the new example-08 ITs must NOT use Testcontainers — that is the point.

---

### Task 1: `TikoOptions.replaceTransport` + substitution in `Tiko`

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java` (line ~198 `startTransportsOrShutdown` call site; method at ~477)
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/TransportReplacementTest.java` (new)

**Interfaces:**
- Consumes: `io.tiko.TransportBootstrap` (unchanged), `io.tiko.ContainerInitializationException` (existing).
- Produces (Task 2/3 rely on these exact signatures):
  - `public <T extends TransportBootstrap> TikoOptions.Builder replaceTransport(Class<T> transport, Function<T, TransportBootstrap> replacement)` — repeatable; duplicate key → `IllegalArgumentException` at registration.
  - Package-private `Map<Class<?>, Function<TransportBootstrap, TransportBootstrap>> TikoOptions.transportReplacements()` (empty map when none).
  - Package-private static `List<TransportBootstrap> Tiko.applyTransportReplacements(List<TransportBootstrap> discovered, TikoOptions options)`.

- [ ] **Step 1: Write the failing tests**

Create `tiko-runtime/src/test/java/io/tiko/runtime/TransportReplacementTest.java`:

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.TransportBootstrap;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportReplacementTest {

    /** Stands in for a generated transport; the marker subtype is the substitution key. */
    static class StubTransport implements TransportBootstrap {
        @Override
        public void start(Container container) {}

        @Override
        public void shutdown() {}
    }

    /** A second, unrelated transport type to prove matching is selective. */
    static class OtherTransport implements TransportBootstrap {
        @Override
        public void start(Container container) {}

        @Override
        public void shutdown() {}
    }

    @Test
    void matchingTransportIsReplaced() {
        var discovered = new StubTransport();
        var replacement = new OtherTransport();
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> replacement)
                .build();

        List<TransportBootstrap> result = Tiko.applyTransportReplacements(List.of(discovered), options);

        assertThat(result).containsExactly(replacement);
    }

    @Test
    void nullResultDropsTheTransport() {
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> null)
                .build();

        List<TransportBootstrap> result =
                Tiko.applyTransportReplacements(List.of(new StubTransport(), new OtherTransport()), options);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(OtherTransport.class);
    }

    @Test
    void baseInterfaceKeyMatchesEveryTransport() {
        var options = TikoOptions.builder()
                .replaceTransport(TransportBootstrap.class, t -> null)
                .build();

        List<TransportBootstrap> result =
                Tiko.applyTransportReplacements(List.of(new StubTransport(), new OtherTransport()), options);

        assertThat(result).isEmpty();
    }

    @Test
    void unmatchedKeyFailsFastNamingDiscoveredTransports() {
        var options = TikoOptions.builder()
                .replaceTransport(OtherTransport.class, t -> t)
                .build();

        assertThatThrownBy(() -> Tiko.applyTransportReplacements(List.of(new StubTransport()), options))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("OtherTransport")
                .hasMessageContaining("StubTransport")
                .hasMessageContaining("Suggested fixes");
    }

    @Test
    void unmatchedKeyWithNoTransportsAtAllStillFailsFast() {
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> t)
                .build();

        assertThatThrownBy(() -> Tiko.applyTransportReplacements(List.of(), options))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("(none)");
    }

    @Test
    void throwingDecoratorIsWrappedWithTheKeyName() {
        var options = TikoOptions.builder()
                .replaceTransport(StubTransport.class, t -> {
                    throw new IllegalStateException("boom");
                })
                .build();

        assertThatThrownBy(() -> Tiko.applyTransportReplacements(List.of(new StubTransport()), options))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("StubTransport")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void noReplacementsReturnsTheDiscoveredListUnchanged() {
        var discovered = List.<TransportBootstrap>of(new StubTransport());

        assertThat(Tiko.applyTransportReplacements(discovered, TikoOptions.builder().build()))
                .isSameAs(discovered);
    }

    @Test
    void duplicateKeyRegistrationThrowsAtBuilderTime() {
        var builder = TikoOptions.builder().replaceTransport(StubTransport.class, t -> t);

        assertThatThrownBy(() -> builder.replaceTransport(StubTransport.class, t -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StubTransport");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-runtime -am -Dtest=TransportReplacementTest *> ..\t1-red.log; $LASTEXITCODE`
Expected: non-zero — `replaceTransport` and `applyTransportReplacements` don't exist (compile error).

- [ ] **Step 3: Implement `TikoOptions` side**

In `TikoOptions.java`:

1. Field (after `overrides`, line ~40):

```java
private final java.util.Map<Class<?>, java.util.function.Function<TransportBootstrap, TransportBootstrap>>
        transportReplacements;
```

2. Import `io.tiko.TransportBootstrap` (the file currently imports from `io.tiko` already).

3. In the private constructor:

```java
this.transportReplacements = b.transportReplacements == null
        ? java.util.Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(b.transportReplacements));
```

4. Package-private accessor (next to the other accessors):

```java
/**
 * Registered transport replacements in registration order; empty when none. Keys are the
 * marker classes passed to {@link Builder#replaceTransport}; values are the decorators,
 * pre-wrapped so the framework can apply them to any discovered {@code TransportBootstrap}.
 */
java.util.Map<Class<?>, java.util.function.Function<TransportBootstrap, TransportBootstrap>>
        transportReplacements() {
    return transportReplacements;
}
```

5. Builder field + method:

```java
private java.util.Map<Class<?>, java.util.function.Function<TransportBootstrap, TransportBootstrap>>
        transportReplacements;
```

```java
/**
 * Replaces every ServiceLoader-discovered {@link TransportBootstrap} that is an instance of
 * {@code transport} with the result of {@code replacement}, applied between discovery and
 * transport start. Returning {@code null} drops the transport for this container — the
 * disable idiom. {@code replaceTransport(TransportBootstrap.class, t -> null)} disables all
 * transports.
 *
 * <p>This is a <strong>test affordance</strong> in the same family as
 * {@link #override(Class, java.util.function.Supplier)}: it exists so integration tests can
 * substitute a fake transport (e.g. {@code FakeKafkaTransport} over a {@code FakeKafkaBroker})
 * for the generated one. Production configuration belongs in the transport's own config keys.
 *
 * @throws IllegalArgumentException if a replacement is already registered for {@code transport}
 * @throws NullPointerException if either argument is null
 */
public <T extends TransportBootstrap> Builder replaceTransport(
        Class<T> transport, java.util.function.Function<T, TransportBootstrap> replacement) {
    Objects.requireNonNull(transport, "transport");
    Objects.requireNonNull(replacement, "replacement");
    if (transportReplacements == null) {
        transportReplacements = new java.util.LinkedHashMap<>();
    }
    if (transportReplacements.containsKey(transport)) {
        throw new IllegalArgumentException(
                "replaceTransport already registered for " + transport.getName());
    }
    transportReplacements.put(transport, tb -> replacement.apply(transport.cast(tb)));
    return this;
}
```

- [ ] **Step 4: Implement the `Tiko` side**

In `Tiko.java`:

1. Change the call at line ~198 from
   `return startTransportsOrShutdown(container, classLoader);` to
   `return startTransportsOrShutdown(container, classLoader, options);`
   (`options` is in scope in `createInternal(TikoOptions options)`).

2. Extend `startTransportsOrShutdown` (line ~477) to apply substitutions inside the existing try (so a failing decorator triggers the same #348 teardown as a failing discovery):

```java
private static Container startTransportsOrShutdown(
        Container container, ClassLoader classLoader, TikoOptions options) {
    java.util.List<TransportBootstrap> bootstraps = new java.util.ArrayList<>();
    try {
        for (TransportBootstrap tb : java.util.ServiceLoader.load(TransportBootstrap.class, classLoader)) {
            bootstraps.add(tb);
        }
        bootstraps = applyTransportReplacements(bootstraps, options);
    } catch (RuntimeException | java.util.ServiceConfigurationError e) {
        shutdownQuietly(container);
        throw e;
    }
    return startTransports(container, bootstraps);
}
```

3. Add the substitution method (near `startTransports`; package-private static for direct testing, same convention as `startTransports`):

```java
/**
 * Applies {@link TikoOptions.Builder#replaceTransport} registrations to the discovered
 * transports, in registration order. Each entry must match at least one discovered
 * transport ({@code Class.isInstance}); a {@code null} decorator result drops the
 * transport. An entry may match several transports — the decorator runs for each.
 */
static java.util.List<TransportBootstrap> applyTransportReplacements(
        java.util.List<TransportBootstrap> discovered, TikoOptions options) {
    var replacements = options.transportReplacements();
    if (replacements.isEmpty()) {
        return discovered;
    }
    java.util.List<TransportBootstrap> result = new java.util.ArrayList<>(discovered);
    for (var entry : replacements.entrySet()) {
        Class<?> key = entry.getKey();
        var decorator = entry.getValue();
        boolean matched = false;
        for (int i = 0; i < result.size(); i++) {
            TransportBootstrap current = result.get(i);
            if (current == null || !key.isInstance(current)) {
                continue;
            }
            matched = true;
            try {
                result.set(i, decorator.apply(current));
            } catch (RuntimeException e) {
                throw new ContainerInitializationException(
                        "replaceTransport(" + key.getName() + ", ...) threw while replacing "
                                + current.getClass().getName(),
                        e);
            }
        }
        if (!matched) {
            java.util.List<String> discoveredNames = result.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(tb -> tb.getClass().getName())
                    .toList();
            throw new ContainerInitializationException("replaceTransport(" + key.getName()
                    + ", ...) matched no discovered transport.\n"
                    + "Discovered transports: "
                    + (discoveredNames.isEmpty() ? "(none)" : discoveredNames)
                    + "\n"
                    + "Suggested fixes:\n"
                    + "1. Check the transport module (runtime + annotation processor) is on the classpath.\n"
                    + "2. Remove the replaceTransport(...) registration if the transport is not part of this app.");
        }
    }
    result.removeIf(java.util.Objects::isNull);
    return result;
}
```

(`ContainerInitializationException` is already imported in `Tiko.java`.)

- [ ] **Step 5: Run to verify green**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-runtime -am *> ..\t1-green.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS`, all tiko-runtime tests pass (full module run — the existing `TikoOptionsTest`, `TransportBootstrapDiscoveryTest`, `TikoBootstrapFailureTest` must stay green).

- [ ] **Step 6: Commit**

```powershell
git add tiko-runtime/src
git commit -m "feat(runtime): class-keyed TikoOptions.replaceTransport applied between transport discovery and start (#414)"
```

---

### Task 2: `KafkaTransport` marker + generator change + `FakeKafkaTransport`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaTransport.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/test/FakeKafkaTransport.java`
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGenerator.java` (superinterface at line ~64; `buildSourcesProvider` ~119-121; `buildSinksProvider` ~150-151)
- Test: `tiko-kafka/src/test/java/io/tiko/kafka/test/FakeKafkaTransportTest.java` (new)
- Test: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGeneratorTest.java` (extend)

**Interfaces:**
- Consumes: Task 1's `replaceTransport` (docs/tests only); existing `KafkaBootstrapSupport` 5-arg public constructor `(Container, List<GeneratedSourceDescriptor>, List<GeneratedSinkDescriptor>, BiFunction<KafkaConfig,String,KafkaConsumerClient>, Function<KafkaConfig,KafkaProducerClient>)`; `FakeKafkaBroker.producerClient()` / `.consumerClient(String group)`.
- Produces (Task 3 relies on): `public interface io.tiko.kafka.KafkaTransport extends TransportBootstrap { List<GeneratedSourceDescriptor> sources(); List<GeneratedSinkDescriptor> sinks(); }`; `public static FakeKafkaTransport io.tiko.kafka.test.FakeKafkaTransport.over(KafkaTransport original, FakeKafkaBroker broker)`; the generated `KafkaTransportBootstrap` implements `KafkaTransport`.

- [ ] **Step 1: Create the marker interface**

`tiko-kafka/src/main/java/io/tiko/kafka/KafkaTransport.java`:

```java
package io.tiko.kafka;

import io.tiko.TransportBootstrap;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor;
import io.tiko.kafka.runtime.GeneratedSourceDescriptor;
import java.util.List;

/**
 * Marker interface implemented by the generated {@code KafkaTransportBootstrap}. Serves two
 * purposes: it is the class key for
 * {@code TikoOptions.builder().replaceTransport(KafkaTransport.class, ...)} (a test
 * affordance in the {@code override(...)} family), and it exposes the compile-time
 * {@code @KafkaSource} / {@code @KafkaSink} descriptors so a replacement transport — chiefly
 * {@link io.tiko.kafka.test.FakeKafkaTransport} — can reuse the generated wiring instead of
 * rebuilding it by hand.
 */
public interface KafkaTransport extends TransportBootstrap {

    /** The generated inbound bridge descriptors, one per {@code @KafkaSource} method. */
    List<GeneratedSourceDescriptor> sources();

    /** The generated outbound bridge descriptors, one per {@code @KafkaSink} method. */
    List<GeneratedSinkDescriptor> sinks();
}
```

- [ ] **Step 2: Write the failing `FakeKafkaTransport` unit test**

`tiko-kafka/src/test/java/io/tiko/kafka/test/FakeKafkaTransportTest.java`:

```java
package io.tiko.kafka.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor;
import io.tiko.kafka.runtime.GeneratedSourceDescriptor;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeKafkaTransportTest {

    /** Hand-built stand-in for the generated bootstrap: real descriptors, no real clients. */
    private static KafkaTransport topology() {
        return new KafkaTransport() {
            @Override
            public List<GeneratedSourceDescriptor> sources() {
                return List.of();
            }

            @Override
            public List<GeneratedSinkDescriptor> sinks() {
                return List.of(new GeneratedSinkDescriptor(
                        "orders-out",
                        "orderId",
                        OrderPlaced.class,
                        KafkaSerializer.Default.class,
                        (container, event) ->
                                container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
                        p -> String.valueOf(((OrderPlaced) p).orderId())));
            }

            @Override
            public void start(Container container) {
                throw new AssertionError("the replaced transport must never be started");
            }

            @Override
            public void shutdown() {}
        };
    }

    @Test
    void routesGeneratedSinkDescriptorsThroughTheFakeBroker() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create()) {
            FakeKafkaTransport fake = FakeKafkaTransport.over(topology(), broker);
            fake.start(container);
            try {
                container.getEventBus().publish(new OrderPlaced("o-9", 4));

                assertThat(broker.produced("orders-out")).hasSize(1);
                assertThat(broker.produced("orders-out").get(0).key()).isEqualTo("o-9");
            } finally {
                fake.shutdown();
            }
        }
    }

    @Test
    void startIsIdempotentAndShutdownDelegates() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create()) {
            FakeKafkaTransport fake = FakeKafkaTransport.over(topology(), broker);
            fake.start(container);
            fake.start(container); // second start must be a no-op (TransportBootstrap contract)

            container.getEventBus().publish(new OrderPlaced("o-1", 1));
            assertThat(broker.produced("orders-out")).hasSize(1);

            fake.shutdown();
            fake.shutdown(); // second shutdown must be a no-op
        }
    }

    @Test
    void substitutionThroughTikoOptionsUsesTheFake() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        // No generated transport is on tiko-kafka's own test classpath, so the discovered
        // list is empty — assert the fail-fast contract holds for the kafka key too.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Tiko.create(TikoOptions.builder()
                        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                        .build()))
                .hasMessageContaining("KafkaTransport");
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka -am -Dtest=FakeKafkaTransportTest *> ..\t2-red.log; $LASTEXITCODE`
Expected: non-zero — `FakeKafkaTransport` does not exist (compile error).

- [ ] **Step 4: Implement `FakeKafkaTransport`**

`tiko-kafka/src/main/java/io/tiko/kafka/test/FakeKafkaTransport.java`:

```java
package io.tiko.kafka.test;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.runtime.KafkaBootstrapSupport;
import java.util.Objects;

/**
 * Test transport that routes a {@link KafkaTransport}'s generated {@code @KafkaSource} /
 * {@code @KafkaSink} descriptors through a {@link FakeKafkaBroker} instead of real Kafka
 * clients. Intended for use with
 * {@code TikoOptions.builder().replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))}
 * — the container then owns this transport's lifecycle like any other.
 *
 * <pre>{@code
 * FakeKafkaBroker broker = new FakeKafkaBroker();
 * try (Container c = Tiko.create(TikoOptions.builder()
 *         .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
 *         .build())) {
 *     broker.produce("orders", orderJson);
 *     // assertions against broker.produced(...) and container state
 * }
 * }</pre>
 */
public final class FakeKafkaTransport implements TransportBootstrap {

    private final KafkaTransport original;
    private final FakeKafkaBroker broker;
    private KafkaBootstrapSupport support;

    private FakeKafkaTransport(KafkaTransport original, FakeKafkaBroker broker) {
        this.original = original;
        this.broker = broker;
    }

    /** Wraps the generated transport's descriptors around {@code broker}'s in-memory clients. */
    public static FakeKafkaTransport over(KafkaTransport original, FakeKafkaBroker broker) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(broker, "broker");
        return new FakeKafkaTransport(original, broker);
    }

    @Override
    public void start(Container container) {
        if (support != null) {
            return;
        }
        support = new KafkaBootstrapSupport(
                container,
                original.sources(),
                original.sinks(),
                (config, group) -> broker.consumerClient(group),
                config -> broker.producerClient());
        support.start();
    }

    @Override
    public void shutdown() {
        if (support != null) {
            support.shutdown();
            support = null;
        }
    }
}
```

- [ ] **Step 5: Run the kafka tests — expect green**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka -am *> ..\t2-kafka-green.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS`.

- [ ] **Step 6: Write the failing generator test**

Add to `KafkaTransportBootstrapGeneratorTest.java` (it already has `compileOrderFixtures()` and `bootstrapSource(...)` helpers from the #267 work — reuse them):

```java
@Test
void generatedBootstrapImplementsKafkaTransportWithPublicDescriptors() throws IOException {
    Compilation compilation = compileOrderFixtures();

    String normalized = bootstrapSource(compilation).replaceAll("\\s", "");
    org.assertj.core.api.Assertions.assertThat(normalized)
            .as("generated bootstrap is substitutable via KafkaTransport and exposes its wiring")
            .contains("implementsKafkaTransport")
            .contains("@OverridepublicList<GeneratedSourceDescriptor>sources()")
            .contains("@OverridepublicList<GeneratedSinkDescriptor>sinks()");
}
```

- [ ] **Step 7: Run to verify failure**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka-processor -am -Dtest=KafkaTransportBootstrapGeneratorTest *> ..\t2-proc-red.log; $LASTEXITCODE`
Expected: non-zero — the new test fails (generated class still `implements TransportBootstrap` with private methods).

- [ ] **Step 8: Update the generator**

In `KafkaTransportBootstrapGenerator.java`:

1. Add a constant next to `TRANSPORT_BOOTSTRAP` (line ~38):

```java
private static final ClassName KAFKA_TRANSPORT = ClassName.get("io.tiko.kafka", "KafkaTransport");
```

2. At line ~64 change `.addSuperinterface(TRANSPORT_BOOTSTRAP)` to `.addSuperinterface(KAFKA_TRANSPORT)` (it extends `TransportBootstrap`, so the generated class still satisfies the SPI). The `TRANSPORT_BOOTSTRAP` constant stays — it is still used by the ServiceLoader entry writer comment context; delete it only if the compiler flags it unused.

3. In `buildSourcesProvider` (line ~119-121) and `buildSinksProvider` (line ~150-151), change

```java
.addModifiers(Modifier.PRIVATE)
```

to

```java
.addModifiers(Modifier.PUBLIC)
.addAnnotation(Override.class)
```

(both builders; keep everything else identical).

- [ ] **Step 9: Run both kafka modules — expect green**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka,tiko-kafka-processor -am *> ..\t2-all-green.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS`. The pre-existing generator/contract/topology tests must pass — the compile-tests compile the generated code against the real `KafkaTransport` interface, proving the implements-relationship is valid.

- [ ] **Step 10: Commit**

```powershell
git add tiko-kafka/src tiko-kafka-processor/src
git commit -m "feat(kafka): KafkaTransport marker on generated bootstrap + FakeKafkaTransport test transport (#414)"
```

---

### Task 3: End-to-end ITs in example 08 (generated code, no Docker)

**Files:**
- Create: `tiko-examples/08_kafka_order_warehouse/order-service/src/test/java/io/tiko/examples/kafka/order/FakeBrokerOrderPublishIT.java`
- Create: `tiko-examples/08_kafka_order_warehouse/warehouse-service/src/test/java/io/tiko/examples/kafka/warehouse/FakeBrokerWarehouseConsumeIT.java`
- Modify: `tiko-examples/08_kafka_order_warehouse/order-service/pom.xml` and `.../warehouse-service/pom.xml` (test dependencies)

**Interfaces:**
- Consumes: Task 1's `replaceTransport(KafkaTransport.class, ...)`, Task 2's `FakeKafkaTransport.over(...)`; app classes `OrderKafkaPublisher` (`@KafkaSink(topic = "orders", partitionKey = "orderId")`), `OrderKafkaConsumer` (`@KafkaSource(topic = "orders")` + `@EventTrigger("OrderPlaced")`), `WarehouseService` (writes each `orderId` to the file named by system property `probe.file`), shared record `OrderPlaced(String orderId, BigDecimal amount, Instant placedAt)`; `JsonKafkaSerializer.serialize(Object)`.
- Produces: the two reference ITs the docs (Task 4) point at. These run under failsafe (`*IT` at `mvn verify`), inherited from the root pom — no per-module failsafe wiring.

**Why two single-container ITs instead of one combined:** both services generate a class named `io.tiko.generated.KafkaTransportBootstrap`; putting both on one test classpath would clash. One IT per service module is also the shape a real user's test takes.

- [ ] **Step 1: Add test dependencies to both service poms**

In `order-service/pom.xml` and `warehouse-service/pom.xml`, add to `<dependencies>` (versions come from the root/BOM dependency management — do not restate them; if the build complains about a missing version, copy the `<version>` from `tiko-kafka/pom.xml`'s equivalent test dependency):

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

Warehouse additionally needs Awaitility (inbound consumption is async — a background poll thread):

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

(Skip any of these already present in the pom.)

- [ ] **Step 2: Write the outbound IT (order-service)**

`FakeBrokerOrderPublishIT.java`:

```java
package io.tiko.examples.kafka.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The #414 reference recipe, outbound half: the app's generated {@code @KafkaSink} wiring
 * publishes to a {@link FakeKafkaBroker} — no Docker, no real Kafka.
 */
class FakeBrokerOrderPublishIT {

    @Test
    void placedOrderIsProducedToTheOrdersTopicWithTheOrderIdKey() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create(TikoOptions.builder()
                .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                .build())) {

            OrderPlaced order = new OrderPlaced("o-42", new BigDecimal("19.99"), Instant.now());
            container.getEventBus().publish(order);

            assertThat(broker.produced("orders")).hasSize(1);
            assertThat(broker.produced("orders").get(0).key()).isEqualTo("o-42");

            OrderPlaced roundTripped =
                    new JsonKafkaSerializer().deserialize(broker.produced("orders").get(0).value(), OrderPlaced.class);
            assertThat(roundTripped.orderId()).isEqualTo("o-42");
        }
    }
}
```

- [ ] **Step 3: Write the inbound IT (warehouse-service)**

`FakeBrokerWarehouseConsumeIT.java`:

```java
package io.tiko.examples.kafka.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The #414 reference recipe, inbound half: a record produced onto the fake broker flows
 * through the app's generated {@code @KafkaSource} bridge into the local event chain.
 */
class FakeBrokerWarehouseConsumeIT {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetProbeProperty() {
        System.clearProperty("probe.file");
    }

    @Test
    void recordOnOrdersTopicReachesTheWarehouseHandler() {
        Path probe = tempDir.resolve("warehouse.probe");
        System.setProperty("probe.file", probe.toString());

        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create(TikoOptions.builder()
                .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                .build())) {

            byte[] payload = new JsonKafkaSerializer()
                    .serialize(new OrderPlaced("o-7", new BigDecimal("5.00"), Instant.now()));
            broker.produce("orders", payload);

            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(probe));
            assertThat(Files.readAllLines(probe)).containsExactly("o-7");
        } catch (java.io.IOException e) {
            throw new AssertionError("probe file could not be read", e);
        }
    }
}
```

- [ ] **Step 4: Run both ITs**

Run: `& W:\tools\apache-maven\bin\mvn.cmd verify -pl tiko-examples/08_kafka_order_warehouse/order-service,tiko-examples/08_kafka_order_warehouse/warehouse-service -am *> ..\t3-it.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS`; the failsafe summary in the log shows the two new ITs ran (`Tests run: 1` per module under `Failsafe`) — verify they actually executed, not just that the build is green. The pre-existing `OrderToWarehouseE2EIT` (Testcontainers) will report Skipped on this machine — that is expected and unrelated.

- [ ] **Step 5: Commit**

```powershell
git add tiko-examples/08_kafka_order_warehouse
git commit -m "test(examples): FakeKafkaBroker ITs for example 08 via replaceTransport - no Docker needed (#414)"
```

---

### Task 4: Documentation — testing.md, tiko-build skill (canonical + archetype), example README

**Files:**
- Modify: `docs/testing.md` (append a new section after the `## TikoOptions.override(...) — runtime overrides` section, currently starting at line ~200)
- Modify: `.ai-skills/tiko-build/SKILL.md` (insert into the `## Kafka transport: write this shape first` section, before `## Anti-pattern redirect table`)
- Modify: `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md` (same edit — the #408 gate requires the bundled copy to stay identical to the canonical)
- Modify: `tiko-examples/08_kafka_order_warehouse/README.md` (if the module has one — check; if absent, skip this file, the ITs are self-documenting)

**Interfaces:**
- Consumes: the API shipped in Tasks 1-3 (exact names: `TikoOptions.builder().replaceTransport`, `KafkaTransport.class`, `FakeKafkaTransport.over`, `FakeKafkaBroker`).
- Produces: the documented recipe — issue #414's second acceptance criterion.

- [ ] **Step 1: Add the `docs/testing.md` section**

Append after the `TikoOptions.override(...)` section:

```markdown
## Faking the Kafka transport — `replaceTransport` + `FakeKafkaTransport`

To integration-test a `@KafkaSource` / `@KafkaSink` app without a broker, replace the
generated Kafka transport with one backed by the in-memory `FakeKafkaBroker`:

​```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) {

    // Outbound: publish the local event; assert the sink produced a record.
    c.getEventBus().publish(new OrderPlaced("o-42", amount, Instant.now()));
    assertThat(broker.produced("orders")).hasSize(1);

    // Inbound: produce onto the fake broker; the @KafkaSource bridge consumes it.
    broker.produce("orders", new JsonKafkaSerializer().serialize(order));
    // consumption is async (a background poll thread) — use Awaitility, not Thread.sleep
}
​```

- `replaceTransport` is a **test affordance** in the `override(...)` family: class-keyed,
  applied between ServiceLoader discovery and transport start. The container owns the
  fake's lifecycle — no separate resource to close.
- Returning `null` from the decorator drops the transport instead
  (`.replaceTransport(TransportBootstrap.class, t -> null)` disables all transports).
- A key that matches no discovered transport fails fast at `Tiko.create(...)` — if you hit
  that in a unit-test module, the generated transport isn't on the test classpath.

Runnable reference: `tiko-examples/08_kafka_order_warehouse` —
`FakeBrokerOrderPublishIT` (outbound) and `FakeBrokerWarehouseConsumeIT` (inbound), both
Docker-free.
```

(Remove the zero-width markers around the code fence when writing the real file — they exist only to nest the fence in this plan.)

- [ ] **Step 2: Add the skill recipe (canonical), then copy to the archetype**

In `.ai-skills/tiko-build/SKILL.md`, at the end of the `## Kafka transport: write this shape first` section (immediately before `## Anti-pattern redirect table`), insert:

```markdown
### Testing Kafka bridges: use the fake broker, never a real one in unit/IT scope

Do NOT try to disable the transport by deleting `META-INF/services` files, hiding the SPI
with classloader tricks, or hand-rebuilding `KafkaBootstrapSupport`. The supported seam is
one option + one helper:

​```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) {
    broker.produce("orders", new JsonKafkaSerializer().serialize(event)); // drive @KafkaSource
    c.getEventBus().publish(outboundEvent);                               // drive @KafkaSink
    assertThat(broker.produced("notifications")).hasSize(1);
}
​```

Inbound consumption is asynchronous (background poll thread): assert with Awaitility
(`await().atMost(...)`), never `Thread.sleep`. Reference ITs:
`tiko-examples/08_kafka_order_warehouse/*/src/test/java/.../FakeBroker*IT.java`.
​```
```

Then overwrite the same section into the archetype copy so the two files stay byte-identical in this section — after editing both, verify:

Run (Git Bash): `diff .ai-skills/tiko-build/SKILL.md tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md && echo IDENTICAL`
Expected: `IDENTICAL` (the #408 gate enforces exactly this; if the two files legitimately differ elsewhere today, STOP and report — do not force them identical beyond your own edit).

- [ ] **Step 3: Example README note**

Check `tiko-examples/08_kafka_order_warehouse/README.md` exists. If yes, add under its testing/e2e section:

```markdown
Two Docker-free integration tests show the supported fake-broker seam (#414):
`order-service/.../FakeBrokerOrderPublishIT` drives the `@KafkaSink` outbound path and
`warehouse-service/.../FakeBrokerWarehouseConsumeIT` the `@KafkaSource` inbound path, via
`TikoOptions.replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))`.
The Testcontainers e2e (`OrderToWarehouseE2EIT`) still covers the real-broker path.
```

If the file does not exist, skip (note it in your report).

- [ ] **Step 4: Full reactor verification**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test *> ..\t4-full.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS` (includes the spotless gate and the archetype's own tests — the #408 skill-sync gate runs there).

- [ ] **Step 5: Commit**

```powershell
git add docs/testing.md .ai-skills/tiko-build/SKILL.md tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md tiko-examples/08_kafka_order_warehouse/README.md
git commit -m "docs(testing): fake-broker recipe for replaceTransport + FakeKafkaTransport in docs, skill, and archetype (#414)"
```

---

### Task 5: Push + PR

**Files:** none (delivery only).

- [ ] **Step 1: Push and open the PR**

Write the body to a scratch file (mention-harvest rule: annotations in backticks, `--body-file`, no heredocs), then:

`pr-body-414.md`:

```markdown
Closes #414.

Adds the supported test seam for driving a tiko app's Kafka transport through `FakeKafkaBroker` — benchmark finding F4, the top cost driver for agent builds.

- `TikoOptions.builder().replaceTransport(Class<T extends TransportBootstrap>, Function<T, TransportBootstrap>)`: class-keyed transport substitution applied between ServiceLoader discovery and transport start. Decorator result replaces the discovered transport; `null` drops it; unmatched keys fail fast with the discovered-transport list. A test affordance in the `override(...)` family — `tiko-api` is unchanged.
- New `KafkaTransport` marker interface (`extends TransportBootstrap`, exposes the generated `sources()`/`sinks()` descriptors); the generated `KafkaTransportBootstrap` now implements it with public descriptor accessors.
- New `io.tiko.kafka.test.FakeKafkaTransport` — `FakeKafkaTransport.over(KafkaTransport, FakeKafkaBroker)` wires the generated descriptors to the fake broker's clients through the existing public `KafkaBootstrapSupport` constructor.
- Example 08 gains two Docker-free ITs (outbound `@KafkaSink`, inbound `@KafkaSource`) that double as the documented reference recipe; recipe added to `docs/testing.md` and the `tiko-build` skill (canonical + archetype bundle).

Test setup for users drops from SPI-file surgery + hand-rebuilt wiring to:

​```java
try (Container c = Tiko.create(TikoOptions.builder()
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) { ... }
​```

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

(Strip the zero-width fence markers when writing the file.)

```powershell
git push -u origin feat/issue-414-transport-substitution
& "C:\Program Files\GitHub CLI\gh.exe" pr create --title "Close #414: class-keyed transport substitution + FakeKafkaTransport test seam" --body-file pr-body-414.md
Remove-Item pr-body-414.md -Confirm:$false
```

- [ ] **Step 2: Post-CI checks**

After CI: `gh pr checks <N> --watch`; then query the SonarCloud open-issues API for the PR (green gate alone is insufficient) and report PR URL + CI/Sonar state. The user merges in the UI.

---

## Self-Review Notes

- Spec coverage: §1→Task 1 (builder), §2→Task 1 (`applyTransportReplacements`), §3+§4+§5→Task 2, §6 acceptance shape→Task 3 ITs, Testing section→Tasks 1-3 test steps, Documentation→Task 4, Delivery→commits per task + Task 5. No gaps.
- Type consistency: `replaceTransport(Class<T>, Function<T, TransportBootstrap>)`, `transportReplacements()` map shape, `applyTransportReplacements(List, TikoOptions)`, `KafkaTransport.sources()/sinks()`, `FakeKafkaTransport.over(KafkaTransport, FakeKafkaBroker)` used identically across Tasks 1-4.
- Deviation from spec, deliberate: the spec's runtime-test list included "replaced transport's shutdown() runs on container close (through TransportAwareContainer)" — covered instead by Task 2's delegation unit test plus Task 3's real-container ITs (the fake's poll threads would leak and Awaitility-based ITs would flake if shutdown didn't propagate); a dedicated runtime test would need a full Container stub for no additional signal.
- Known environment note for Task 3: example modules build against the reactor's `0.4.0-SNAPSHOT` artifacts via `-am`; no local `mvn install` needed.
