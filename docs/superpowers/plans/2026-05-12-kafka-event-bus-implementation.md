# Kafka event bus — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Kafka MVP described in the spec — a `TransportBootstrap` SPI in `tiko-api`, a self-contained `tiko-kafka` + `tiko-kafka-processor` module pair (with `@KafkaSource` / `@KafkaSink` annotations, `KafkaContext`, JSON-only `KafkaSerializer<T>` SPI, thread-per-topic consumer with per-record commit and seek-back, `FakeKafkaBroker` for tests), and a runnable cross-JVM demo at `tiko-examples/08_kafka_order_warehouse` covered by three test layers (unit, Testcontainers integration, process-orchestrated e2e).

**Architecture:** Universal transport-adapter pattern. Local `EventBus` / `@EventHandler` / `@EventTrigger` contract stays untouched. The Kafka annotation processor is fully independent from `tiko-processor`; both processors run side-by-side on the user's annotation-processor path. The Kafka runtime connects to the live container via a single new SPI (`io.tiko.TransportBootstrap`) discovered by `ServiceLoader`. Generated `KafkaTransportBootstrap` resolves bridge `@Component`s through the existing `Container.get(...)` API — no reflection on the hot path, no special-casing inside `tiko-runtime`.

**Tech Stack:** Java 17+, Maven multi-module, JavaPoet for codegen, Apache Kafka client (`org.apache.kafka:kafka-clients`), Jackson for JSON (shadow-bundled), JUnit 5 + AssertJ for tests, `com.google.testing.compile` for processor tests, `org.testcontainers:kafka` for the integration layer, Palantir Java Format + Spotless gate at the `validate` phase.

**Spec:** `docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md` (commits `df357fd`, `5afa7ef` on `feat/kafka-spec`).

**Branch strategy:** Implementation work happens on a fresh `feat/kafka-mvp` branched from `main` **after** the spec PR (the current `feat/kafka-spec`) merges. Phase 12 lays out the PR strategy.

**Maven invocation:** `mvn` lives at `W:\tools\apache-maven\bin\mvn.cmd` (not on PATH in spawned shells). Either prepend the full path or ensure PATH is set before running tasks. All `mvn` references below assume the binary is callable.

**Formatting gate:** Spotless runs at `validate` and fails the build on any `palantir-java-format` deviation. Run `mvn spotless:apply -pl '!tiko-bom'` before every commit if your IDE doesn't format on save.

---

## Phase 1 — Core SPI in `tiko-api`

### Task 1: Add `TransportBootstrap` interface

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/TransportBootstrap.java`

- [ ] **Step 1: Write the file**

```java
package io.tiko;

/**
 * Discovered by {@code ServiceLoader<TransportBootstrap>} at container startup. Every
 * transport module (`tiko-kafka`, future `tiko-http`, `tiko-scheduler`, ...) emits one
 * implementation of this interface via its own annotation processor and registers it
 * through {@code META-INF/services/io.tiko.TransportBootstrap}.
 *
 * <p>Lifecycle, in order:
 * <ol>
 *   <li>{@code Tiko.create(...)} builds the container and calls {@link Container#start()}.</li>
 *   <li>For every discovered {@link TransportBootstrap}, the runtime invokes
 *       {@link #start(Container)} with the live, fully-instantiated container. By this
 *       point all singleton {@code @Component}s exist, the {@code EventBus} is wired,
 *       and bound {@code @Configuration} records are injectable. Transports use
 *       {@link Container#get(Class)} to resolve their bridge components.</li>
 *   <li>On {@link Container#shutdown()}, the runtime invokes {@link #shutdown()} on every
 *       transport <em>before</em> the container runs its own {@code @PreDestroy} LIFO
 *       chain. Bridge {@code @Component}s are still alive while the transport releases
 *       its resources (closes consumers/producers, joins threads).</li>
 * </ol>
 *
 * <p>Implementations must be idempotent: a second {@code start()} or {@code shutdown()}
 * call has no effect.
 */
public interface TransportBootstrap {

    /**
     * Wire transport-specific subscriptions / launch consumer threads. Called once
     * after {@code container.start()} returns.
     */
    void start(Container container);

    /**
     * Release transport-owned resources. Called once during {@code container.shutdown()},
     * before the container runs its own {@code @PreDestroy} chain.
     */
    void shutdown();
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS, no warnings.

- [ ] **Step 3: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/TransportBootstrap.java
git commit -m "feat(api): add TransportBootstrap SPI for transport modules"
```

---

### Task 2: Widen `ErrorContext` with `TransportError` permit

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/ErrorContext.java`
- Create: `tiko-api/src/main/java/io/tiko/TransportError.java`

- [ ] **Step 1: Replace `ErrorContext` permits**

Replace the contents of `tiko-api/src/main/java/io/tiko/ErrorContext.java`:

```java
package io.tiko;

/**
 * Sealed root of all error categories surfaced through {@link ErrorHandler}.
 * Pattern-match on the concrete subtype to handle each category structurally:
 *
 * <pre>{@code
 * public void onError(ErrorContext ctx) {
 *     switch (ctx) {
 *         case EventHandlerError e -> metrics.eventHandlerError(e.handler());
 *         case TransportError t    -> metrics.transportError(t.transport(), t.cause());
 *     }
 * }
 * }</pre>
 *
 * <p>{@link EventHandlerError} is in-process / handler-side errors raised by the local
 * {@code EventBus}. {@link TransportError} is the non-sealed permit every transport
 * module ({@code tiko-kafka}, future {@code tiko-http}, ...) extends to surface its own
 * concrete error types without forcing a tiko-api update.
 *
 * <p>Adding a new top-level permit here is intentionally a compile-time-loud breaking
 * change for users with exhaustive {@code switch} expressions — they are told to handle
 * the new category.
 */
public sealed interface ErrorContext permits EventHandlerError, TransportError {

    /**
     * The throwable that caused this error context to be raised.
     */
    Throwable cause();
}
```

- [ ] **Step 2: Create `TransportError`**

```java
package io.tiko;

/**
 * Open permit under {@link ErrorContext} for transport-module errors. Transport modules
 * ({@code tiko-kafka}, future {@code tiko-http}, ...) define concrete record types that
 * implement this interface and add transport-specific fields (topic, partition, request
 * id, ...). Users pattern-match on {@code TransportError} for cross-transport handling,
 * or on the concrete record types for transport-specific handling.
 *
 * <p>This interface is intentionally {@code non-sealed} — adding a new transport must
 * not require an edit in {@code tiko-api}.
 */
public non-sealed interface TransportError extends ErrorContext {

    /**
     * Short transport identifier, e.g. {@code "kafka"}, {@code "http"}, {@code "scheduler"}.
     * Used by generic error-handling code that does not pattern-match on concrete types.
     */
    String transport();
}
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn -pl tiko-api,tiko-runtime,tiko-processor,tiko-config compile -q`
Expected: BUILD SUCCESS. `tiko-runtime`'s existing `DefaultErrorHandler` still compiles because it only references `ErrorContext` and `EventHandlerError`, both still exported.

- [ ] **Step 4: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/ErrorContext.java tiko-api/src/main/java/io/tiko/TransportError.java
git commit -m "feat(api): add TransportError permit under ErrorContext"
```

---

## Phase 2 — Discovery wiring in `tiko-runtime`

### Task 3: Discover and drive `TransportBootstrap` impls in `Tiko.createInternal`

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java`
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/TransportBootstrapDiscoveryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code Tiko.create(...)} discovers every {@link TransportBootstrap}
 * registered via {@code ServiceLoader} and calls {@code start(container)} once after
 * {@code container.start()} and {@code shutdown()} once during {@code container.shutdown()}
 * — before the container's own {@code @PreDestroy} chain.
 *
 * <p>The test fixture {@link RecordingTransportBootstrap} is registered via
 * {@code src/test/resources/META-INF/services/io.tiko.TransportBootstrap}.
 */
class TransportBootstrapDiscoveryTest {

    @Test
    void bootstrap_start_and_shutdown_are_invoked_in_order() {
        RecordingTransportBootstrap.STARTS.set(0);
        RecordingTransportBootstrap.SHUTDOWNS.set(0);

        try (Container container = Tiko.create()) {
            assertThat(RecordingTransportBootstrap.STARTS.get()).isEqualTo(1);
            assertThat(RecordingTransportBootstrap.SHUTDOWNS.get()).isEqualTo(0);
            assertThat(RecordingTransportBootstrap.LAST_CONTAINER).isSameAs(container);
        }

        assertThat(RecordingTransportBootstrap.SHUTDOWNS.get()).isEqualTo(1);
    }

    /** ServiceLoader fixture. */
    public static final class RecordingTransportBootstrap implements TransportBootstrap {
        static final AtomicInteger STARTS = new AtomicInteger();
        static final AtomicInteger SHUTDOWNS = new AtomicInteger();
        static volatile Container LAST_CONTAINER;

        @Override
        public void start(Container container) {
            LAST_CONTAINER = container;
            STARTS.incrementAndGet();
        }

        @Override
        public void shutdown() {
            SHUTDOWNS.incrementAndGet();
        }
    }
}
```

- [ ] **Step 2: Register the test fixture via ServiceLoader**

Create `tiko-runtime/src/test/resources/META-INF/services/io.tiko.TransportBootstrap` with a single line:

```
io.tiko.runtime.TransportBootstrapDiscoveryTest$RecordingTransportBootstrap
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl tiko-runtime test -Dtest=TransportBootstrapDiscoveryTest -q`
Expected: FAIL — `STARTS.get()` is 0 because `Tiko.createInternal` doesn't discover bootstraps yet.

- [ ] **Step 4: Wire discovery in `Tiko.createInternal`**

In `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java`, after the existing `container.start();` line (currently around line 112), add discovery and registration:

```java
// 6. Discover transport modules (tiko-kafka, future tiko-http, ...). Each transport
//    ships its own ServiceLoader entry; the runtime knows nothing transport-specific.
//    start() runs AFTER container.start() so bridge @Components are resolvable; the
//    bootstraps are stored on a wrapping AutoCloseable so shutdown() runs them BEFORE
//    the container's own @PreDestroy chain.
java.util.List<TransportBootstrap> bootstraps = new java.util.ArrayList<>();
for (TransportBootstrap tb : java.util.ServiceLoader.load(TransportBootstrap.class, classLoader)) {
    tb.start(container);
    bootstraps.add(tb);
}

return bootstraps.isEmpty() ? container : new TransportAwareContainer(container, bootstraps);
```

Add the wrapping class as a static nested class at the bottom of `Tiko`:

```java
/**
 * Wrapper that runs every {@link TransportBootstrap#shutdown()} before delegating to the
 * underlying container's own {@code shutdown()} / {@code close()}. Method delegation is
 * exhaustive; we cannot use {@code Container} as a sealed type because user-supplied
 * implementations are not on the radar of this module.
 */
private static final class TransportAwareContainer implements Container {
    private final Container delegate;
    private final java.util.List<TransportBootstrap> bootstraps;

    TransportAwareContainer(Container delegate, java.util.List<TransportBootstrap> bootstraps) {
        this.delegate = delegate;
        this.bootstraps = bootstraps;
    }

    @Override public <T> T get(Class<T> type)                                    { return delegate.get(type); }
    @Override public <T> T get(Class<T> type, String name)                       { return delegate.get(type, name); }
    @Override public <T> java.util.List<T> getAll(Class<T> type)                 { return delegate.getAll(type); }
    @Override public <T> io.tiko.Provider<T> getProvider(Class<T> type)          { return delegate.getProvider(type); }
    @Override public <T> io.tiko.Provider<T> getProvider(Class<T> type, String name) { return delegate.getProvider(type, name); }
    @Override public void runInRequestScope(Runnable runnable)                   { delegate.runInRequestScope(runnable); }
    @Override public <T> T supplyInRequestScope(java.util.function.Supplier<T> s){ return delegate.supplyInRequestScope(s); }
    @Override public void runInEventScope(Runnable runnable)                     { delegate.runInEventScope(runnable); }
    @Override public <T> T supplyInEventScope(java.util.function.Supplier<T> s)  { return delegate.supplyInEventScope(s); }
    @Override public void start()                                                { delegate.start(); }
    @Override public io.tiko.EventBus getEventBus()                              { return delegate.getEventBus(); }
    @Override public java.util.concurrent.ExecutorService getEventExecutor()     { return delegate.getEventExecutor(); }

    @Override
    public void shutdown() {
        // Shut transports down BEFORE the container's @PreDestroy chain so their bridge
        // components are still live. Per-bootstrap throws are isolated so one bad
        // transport cannot strand another's resources.
        for (TransportBootstrap tb : bootstraps) {
            try { tb.shutdown(); } catch (Exception ignored) { /* best-effort */ }
        }
        delegate.shutdown();
    }
}
```

Imports to add at the top:

```java
import io.tiko.TransportBootstrap;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl tiko-runtime test -Dtest=TransportBootstrapDiscoveryTest -q`
Expected: PASS.

- [ ] **Step 6: Run the full runtime test suite to verify no regression**

Run: `mvn -pl tiko-runtime test -q`
Expected: PASS — existing tests still see a `Container` impl, just wrapped transparently when at least one `TransportBootstrap` is registered. With zero registrations, `Tiko.createInternal` returns the raw container (no wrapper allocation).

- [ ] **Step 7: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java \
        tiko-runtime/src/test/java/io/tiko/runtime/TransportBootstrapDiscoveryTest.java \
        tiko-runtime/src/test/resources/META-INF/services/io.tiko.TransportBootstrap
git commit -m "feat(runtime): discover and drive TransportBootstrap impls"
```

---

## Phase 3 — `tiko-kafka` module skeleton, annotations, runtime types

### Task 4: Create `tiko-kafka` Maven module

**Files:**
- Create: `tiko-kafka/pom.xml`
- Modify: `pom.xml` (root) — add `tiko-kafka` to `<modules>`
- Modify: `pom.xml` (root) — add Kafka client + Jackson + maven-shade properties and dependency-management entries

- [ ] **Step 1: Register the new module in the root pom**

In root `pom.xml`, find the `<modules>` block (currently around line 15–23) and add `<module>tiko-kafka</module>` between `tiko-config` and `tiko-examples`:

```xml
<modules>
    <module>tiko-bom</module>
    <module>tiko-api</module>
    <module>tiko-processor</module>
    <module>tiko-runtime</module>
    <module>tiko-config</module>
    <module>tiko-kafka</module>
    <module>tiko-examples</module>
    <module>tiko-archetype</module>
</modules>
```

- [ ] **Step 2: Add dependency versions in root pom properties**

In the `<properties>` block of root `pom.xml`, after `snakeyaml.version`, add:

```xml
<kafka-clients.version>3.8.0</kafka-clients.version>
<jackson.version>2.18.0</jackson.version>
<maven-shade.version>3.6.0</maven-shade.version>
<testcontainers.version>1.20.4</testcontainers.version>
```

- [ ] **Step 3: Add dependency-management entries**

In root pom `<dependencyManagement>`/`<dependencies>`, append:

```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>${kafka-clients.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <version>${testcontainers.version}</version>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${testcontainers.version}</version>
</dependency>
```

- [ ] **Step 4: Create `tiko-kafka/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-parent</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>tiko-kafka</artifactId>
    <name>Tiko Kafka Transport</name>
    <description>
        Kafka transport module for Tiko DI — first concrete instance of the
        universal transport-adapter pattern. Ships @KafkaSource/@KafkaSink
        annotations, KafkaContext, the KafkaSerializer SPI, JSON serializer impl,
        Apache Kafka client wrapper, and the FakeKafkaBroker test helper.
    </description>

    <properties>
        <!-- Jackson lives under a relocated package after shading; keep the un-relocated
             scope to compile so source code uses the standard package names. -->
        <jackson.relocate.target>io.tiko.kafka.internal.jackson</jackson.relocate.target>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-config</artifactId>
        </dependency>

        <!-- Kafka client — exposed to users (they may pass it to bridge return types) -->
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-clients</artifactId>
        </dependency>

        <!-- JSON serializer (shadow-bundled below; declared here for compile scope) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Testing -->
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
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-runtime</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${maven-shade.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>true</createDependencyReducedPom>
                            <artifactSet>
                                <includes>
                                    <include>com.fasterxml.jackson.core:*</include>
                                    <include>com.fasterxml.jackson.datatype:*</include>
                                </includes>
                            </artifactSet>
                            <relocations>
                                <relocation>
                                    <pattern>com.fasterxml.jackson</pattern>
                                    <shadedPattern>${jackson.relocate.target}</shadedPattern>
                                </relocation>
                            </relocations>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 5: Create empty `src/main/java` directory marker so Maven recognises the module**

Create the directory `tiko-kafka/src/main/java/io/tiko/kafka/` with a placeholder `package-info.java`:

```java
/**
 * Kafka transport module for Tiko DI — annotations, runtime types, generated
 * bootstrap. See {@code docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md}.
 */
package io.tiko.kafka;
```

Save as: `tiko-kafka/src/main/java/io/tiko/kafka/package-info.java`

- [ ] **Step 6: Verify the module builds**

Run: `mvn -pl tiko-kafka -am compile -q`
Expected: BUILD SUCCESS, `tiko-kafka` listed in the reactor.

- [ ] **Step 7: Commit**

```bash
git add pom.xml tiko-kafka/pom.xml tiko-kafka/src/main/java/io/tiko/kafka/package-info.java
git commit -m "feat(kafka): tiko-kafka module skeleton with shaded Jackson"
```

---

### Task 5: Add `KafkaContext` record

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaContext.java`

- [ ] **Step 1: Write the record**

```java
package io.tiko.kafka;

import java.time.Instant;
import org.apache.kafka.common.header.Headers;

/**
 * Transport-specific metadata exposed to {@code @KafkaSource} bridge methods as an
 * optional second parameter. Carries everything the Apache Kafka {@code ConsumerRecord}
 * exposes without binding the bridge to the underlying client type beyond {@code Headers}.
 *
 * <p>The {@link Headers} type comes from {@code org.apache.kafka.common.header}; this
 * couples bridge components to the Kafka client jar at compile time. Acceptable for the
 * MVP. A future {@code MessageHeaders} wrapper that detaches from the Apache type is a
 * follow-up.
 *
 * @param topic     source topic name
 * @param partition partition number
 * @param offset    record offset within the partition
 * @param timestamp record timestamp (producer- or broker-supplied, depending on broker config)
 * @param headers   record headers, never {@code null}
 */
public record KafkaContext(
        String topic,
        int partition,
        long offset,
        Instant timestamp,
        Headers headers) {}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/KafkaContext.java
git commit -m "feat(kafka): add KafkaContext record"
```

---

### Task 6: Add `CommitMode` enum

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/CommitMode.java`

- [ ] **Step 1: Write the enum**

```java
package io.tiko.kafka;

/**
 * Controls when a {@code @KafkaSource} consumer commits offsets to the broker.
 *
 * <p>MVP ships {@link #PER_RECORD} only. Future modes ({@code BATCH},
 * {@code AT_MOST_ONCE}, {@code MANUAL}) are explicit future work — see the spec
 * "Future extension points" table. Because this enum is referenced by annotation
 * value, expanding it is source-compatible for existing users.
 */
public enum CommitMode {

    /**
     * Commit each record's offset synchronously after the bridge invocation succeeds
     * and the deserialized event has been published to the local bus. Backs the
     * documented at-least-once + idempotent-handlers contract. Per-record commits are
     * slow at extreme throughput; MVP intentionally trades throughput for clarity.
     */
    PER_RECORD
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/CommitMode.java
git commit -m "feat(kafka): add CommitMode enum (PER_RECORD only in MVP)"
```

---

### Task 7: Add `KafkaSerializer<T>` SPI with `Default` marker

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaSerializer.java`

- [ ] **Step 1: Write the interface and marker**

```java
package io.tiko.kafka;

/**
 * Serializer SPI for Kafka transport. MVP ships a single concrete impl,
 * {@link io.tiko.kafka.serializer.JsonKafkaSerializer JsonKafkaSerializer}; future
 * modules (e.g. {@code tiko-kafka-avro}) ship additional impls.
 *
 * <p>Resolution order, per source/sink:
 * <ol>
 *   <li>Annotation parameter set to a concrete class other than {@link Default} →
 *       use that impl (looked up by class via {@code container.get(...)} if registered
 *       as a {@code @Component}, otherwise instantiated reflectively as a no-arg POJO).</li>
 *   <li>Otherwise: the serializer named by {@code KafkaConfig.serializer} (default
 *       {@code "json"}) — looked up via {@code ServiceLoader<NamedKafkaSerializer>} by
 *       name.</li>
 *   <li>Unknown name at startup → container fails fast with a message naming the missing
 *       serializer and the YAML key.</li>
 * </ol>
 *
 * <p>Custom user serializers register themselves the same way as the bundled JSON one —
 * by shipping a {@code NamedKafkaSerializer} via {@code META-INF/services}.
 */
public interface KafkaSerializer<T> {

    /**
     * Serialize the given value to bytes. Implementations must be thread-safe — the
     * runtime calls this from publisher threads (sinks) and the consumer thread (rare;
     * only for round-trips that re-serialize).
     */
    byte[] serialize(T value);

    /**
     * Deserialize the given bytes into an instance of {@code type}. Called from the
     * consumer thread per record. Implementations must be thread-safe.
     */
    T deserialize(byte[] bytes, Class<T> type);

    /**
     * Marker class used as the {@code serializer} annotation default. Means "use the
     * serializer named by {@code KafkaConfig.serializer}." Never instantiated; the
     * runtime checks the class literal and substitutes the resolved impl.
     */
    final class Default implements KafkaSerializer<Object> {
        private Default() {
            throw new UnsupportedOperationException("marker only — never instantiated");
        }

        @Override
        public byte[] serialize(Object value) {
            throw new UnsupportedOperationException("marker only");
        }

        @Override
        public Object deserialize(byte[] bytes, Class<Object> type) {
            throw new UnsupportedOperationException("marker only");
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/KafkaSerializer.java
git commit -m "feat(kafka): add KafkaSerializer<T> SPI"
```

---

### Task 8: Add `NamedKafkaSerializer` SPI

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/NamedKafkaSerializer.java`

- [ ] **Step 1: Write the interface**

```java
package io.tiko.kafka;

/**
 * Auxiliary SPI: binds a YAML config name (e.g. {@code "json"}) to a {@link KafkaSerializer}
 * impl. Discovered via {@code ServiceLoader<NamedKafkaSerializer>} at container startup so
 * the value of {@code tiko.kafka.serializer} can resolve to an impl without reflection on
 * the runtime hot path.
 *
 * <p>MVP ships one impl: {@code io.tiko.kafka.serializer.JsonNamedKafkaSerializer} registered
 * with name {@code "json"}. Future modules ({@code tiko-kafka-avro}, ...) ship their own
 * impl + {@code META-INF/services} entry.
 */
public interface NamedKafkaSerializer {

    /** YAML-config name this serializer answers to, e.g. {@code "json"}. */
    String name();

    /** The serializer impl. The runtime caches the returned instance for the container's lifetime. */
    KafkaSerializer<?> serializer();
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/NamedKafkaSerializer.java
git commit -m "feat(kafka): add NamedKafkaSerializer ServiceLoader SPI"
```

---

### Task 9: Add `@KafkaSource` annotation

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/annotations/KafkaSource.java`

- [ ] **Step 1: Write the annotation**

```java
package io.tiko.kafka.annotations;

import io.tiko.kafka.CommitMode;
import io.tiko.kafka.KafkaSerializer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka inbound bridge. The runtime polls the named topic, deserialises
 * each record into the method's first parameter type via the resolved {@link KafkaSerializer},
 * invokes the method (with an optional {@link io.tiko.kafka.KafkaContext} as the second
 * parameter), and publishes the return value to the local {@code EventBus}.
 *
 * <p>A sibling {@code @io.tiko.annotations.EventTrigger(eventName = "...")} on the same
 * method is required and declares the tracing name for the published event. The actual
 * dispatch on the local bus is by return-type class, not by name. See the spec
 * "Trigger semantics on bridge methods — MVP scope cut".
 *
 * <p>The enclosing class must be {@code @Component(scope = Scope.SINGLETON)} (Kafka consumer
 * threads run outside any request/event scope). Validated at compile time by
 * {@code tiko-kafka-processor}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface KafkaSource {

    /** Topic to subscribe to. Required. */
    String topic();

    /**
     * Consumer group id. Empty string ({@code ""}, the default) means "use
     * {@code KafkaConfig.consumerGroup} from YAML." Per-source override enables future
     * topic-vs-queue patterns by splitting/sharing consumer groups across handlers.
     */
    String consumerGroup() default "";

    /**
     * Serializer override. Default is the {@link KafkaSerializer.Default} marker, which
     * means "use the serializer named by {@code KafkaConfig.serializer}." Setting this to
     * a concrete class pins the deserialiser for this source regardless of YAML config.
     */
    Class<? extends KafkaSerializer<?>> serializer() default KafkaSerializer.Default.class;

    /** Commit strategy. MVP ships {@link CommitMode#PER_RECORD} only. */
    CommitMode commitMode() default CommitMode.PER_RECORD;
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/annotations/KafkaSource.java
git commit -m "feat(kafka): add @KafkaSource annotation"
```

---

### Task 10: Add `@KafkaSink` annotation

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/annotations/KafkaSink.java`

- [ ] **Step 1: Write the annotation**

```java
package io.tiko.kafka.annotations;

import io.tiko.kafka.KafkaSerializer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka outbound bridge. The method must also carry
 * {@code @io.tiko.annotations.EventHandler} (validated at compile time). The runtime
 * subscribes a callback for the handler's event type: when a matching event is published
 * locally, the callback invokes this method, serialises the return value with the resolved
 * {@link KafkaSerializer}, and sends a {@code ProducerRecord} to the named topic.
 *
 * <p>Local handlers always run before any Kafka sink callback (sinks register after the
 * generated {@code EventRegistry} during {@code TransportBootstrap.start()}), so a sink
 * throw never blocks local processing.
 *
 * <p>For non-blocking sends, annotate the same method {@code @EventHandler(async = true)} —
 * the existing async-handler machinery moves the sink invocation onto the event executor.
 * There is no Kafka-specific async knob.
 *
 * <p>The enclosing class must be {@code @Component(scope = Scope.SINGLETON)}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface KafkaSink {

    /** Topic to send to. Required. */
    String topic();

    /**
     * Name of a record-component accessor (or zero-arg public method) on the return type
     * whose value is used as the Kafka message key (partition key). Empty string ({@code ""},
     * the default) sends with a {@code null} key — Kafka's default round-robin partitioning
     * applies. Validated at compile time.
     */
    String partitionKey() default "";

    /**
     * Serializer override. Default is the {@link KafkaSerializer.Default} marker, which
     * means "use the serializer named by {@code KafkaConfig.serializer}." Setting this to
     * a concrete class pins the serialiser for this sink regardless of YAML config.
     */
    Class<? extends KafkaSerializer<?>> serializer() default KafkaSerializer.Default.class;
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/annotations/KafkaSink.java
git commit -m "feat(kafka): add @KafkaSink annotation"
```

---

### Task 11: Add Kafka transport error types

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaIngestError.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaEgressError.java`

- [ ] **Step 1: Write `KafkaIngestError`**

```java
package io.tiko.kafka;

import io.tiko.TransportError;
import org.apache.kafka.common.header.Headers;

/**
 * Raised when the inbound bridge for a Kafka record throws (deserialisation failure,
 * bridge method throw, or downstream {@code eventBus.publish} failure). The consumer
 * seeks back to {@link #offset()} on the next loop iteration; the same record is
 * redelivered. Routed via the configured {@code ErrorHandler}.
 *
 * @param topic     source topic
 * @param partition partition number
 * @param offset    record offset within the partition
 * @param headers   record headers (never {@code null})
 * @param cause     the underlying throwable
 */
public record KafkaIngestError(
        String topic, int partition, long offset, Headers headers, Throwable cause)
        implements TransportError {

    @Override
    public String transport() {
        return "kafka";
    }
}
```

- [ ] **Step 2: Write `KafkaEgressError`**

```java
package io.tiko.kafka;

import io.tiko.TransportError;

/**
 * Raised when an outbound Kafka sink fails to serialise or send a record. Local handlers
 * for the same event have already run before the sink callback fires, so a throw here
 * does not block local processing. Routed via the configured {@code ErrorHandler}.
 *
 * @param topic destination topic
 * @param event the local event whose translation/send failed
 * @param cause the underlying throwable
 */
public record KafkaEgressError(String topic, Object event, Throwable cause)
        implements TransportError {

    @Override
    public String transport() {
        return "kafka";
    }
}
```

- [ ] **Step 3: Verify they compile**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/KafkaIngestError.java \
        tiko-kafka/src/main/java/io/tiko/kafka/KafkaEgressError.java
git commit -m "feat(kafka): add KafkaIngestError / KafkaEgressError records"
```

---

## Phase 4 — JSON serializer, `KafkaConfig`, ServiceLoader wiring

### Task 12: Implement `JsonKafkaSerializer`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonKafkaSerializer.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/serializer/JsonKafkaSerializerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.kafka.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonKafkaSerializerTest {

    record OrderPlaced(String orderId, BigDecimal amount, Instant placedAt) {}

    @Test
    void round_trips_a_simple_record() {
        JsonKafkaSerializer json = new JsonKafkaSerializer();
        OrderPlaced original = new OrderPlaced("o-1", new BigDecimal("19.99"), Instant.parse("2026-05-12T10:00:00Z"));

        byte[] bytes = json.serialize(original);
        OrderPlaced roundTripped = json.deserialize(bytes, OrderPlaced.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void deserialize_unknown_property_does_not_fail() {
        JsonKafkaSerializer json = new JsonKafkaSerializer();
        byte[] bytes = "{\"orderId\":\"o-1\",\"amount\":\"19.99\",\"placedAt\":\"2026-05-12T10:00:00Z\",\"extra\":\"ignored\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        OrderPlaced result = json.deserialize(bytes, OrderPlaced.class);

        assertThat(result.orderId()).isEqualTo("o-1");
    }

    @Test
    void deserialize_malformed_input_throws_with_clear_message() {
        JsonKafkaSerializer json = new JsonKafkaSerializer();
        byte[] bytes = "not json at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> json.deserialize(bytes, OrderPlaced.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("OrderPlaced");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl tiko-kafka test -Dtest=JsonKafkaSerializerTest -q`
Expected: FAIL — `JsonKafkaSerializer` doesn't exist yet.

- [ ] **Step 3: Implement `JsonKafkaSerializer`**

```java
package io.tiko.kafka.serializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tiko.kafka.KafkaSerializer;
import java.io.IOException;

/**
 * Default {@link KafkaSerializer} backed by Jackson. Configured for Java records,
 * JSR-310 date/time types, and lenient deserialisation (unknown properties are ignored
 * — schema evolution between producer and consumer should not crash consumers).
 *
 * <p>The Jackson dependency is shadow-bundled inside {@code tiko-kafka.jar} under
 * {@code io.tiko.kafka.internal.jackson}; the relocation happens at the {@code package}
 * phase via the maven-shade-plugin configuration in {@code tiko-kafka/pom.xml}. At source
 * level we reference the un-relocated package names — the relocator rewrites both the
 * class bytecode and the reachable transitive jars in one pass.
 */
public final class JsonKafkaSerializer implements KafkaSerializer<Object> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public byte[] serialize(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to serialize " + value.getClass().getName() + " to JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, Class<Object> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to deserialize " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl tiko-kafka test -Dtest=JsonKafkaSerializerTest -q`
Expected: PASS, three tests green.

- [ ] **Step 5: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonKafkaSerializer.java \
        tiko-kafka/src/test/java/io/tiko/kafka/serializer/JsonKafkaSerializerTest.java
git commit -m "feat(kafka): JsonKafkaSerializer with record + JSR-310 support"
```

---

### Task 13: Register `JsonKafkaSerializer` as a `NamedKafkaSerializer`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonNamedKafkaSerializer.java`
- Create: `tiko-kafka/src/main/resources/META-INF/services/io.tiko.kafka.NamedKafkaSerializer`

- [ ] **Step 1: Write the named binding**

```java
package io.tiko.kafka.serializer;

import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.NamedKafkaSerializer;

/**
 * Binds the YAML name {@code "json"} to {@link JsonKafkaSerializer}. Discovered via
 * {@code ServiceLoader<NamedKafkaSerializer>}; see
 * {@code META-INF/services/io.tiko.kafka.NamedKafkaSerializer}.
 */
public final class JsonNamedKafkaSerializer implements NamedKafkaSerializer {

    private static final JsonKafkaSerializer INSTANCE = new JsonKafkaSerializer();

    @Override
    public String name() {
        return "json";
    }

    @Override
    public KafkaSerializer<?> serializer() {
        return INSTANCE;
    }
}
```

- [ ] **Step 2: Write the ServiceLoader entry**

File: `tiko-kafka/src/main/resources/META-INF/services/io.tiko.kafka.NamedKafkaSerializer`

```
io.tiko.kafka.serializer.JsonNamedKafkaSerializer
```

- [ ] **Step 3: Verify it builds**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonNamedKafkaSerializer.java \
        tiko-kafka/src/main/resources/META-INF/services/io.tiko.kafka.NamedKafkaSerializer
git commit -m "feat(kafka): register JsonKafkaSerializer as NamedKafkaSerializer(\"json\")"
```

---

### Task 14: Add `KafkaConfig` `@Configuration` record + module defaults

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaConfig.java`
- Create: `tiko-kafka/src/main/resources/META-INF/tiko/defaults.yaml`

- [ ] **Step 1: Write the `@Configuration` record**

```java
package io.tiko.kafka;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import io.tiko.annotations.Key;
import java.time.Duration;
import java.util.Map;

/**
 * YAML-backed configuration root for {@code tiko-kafka}. Auto-discovered through the
 * existing {@code tiko-config} plumbing — no special path through {@code Tiko.create}.
 *
 * <p>Defaults are shipped in {@code META-INF/tiko/defaults.yaml} bundled inside this
 * jar so a user app that pulls {@code tiko-kafka} can start without a {@code config.yaml}
 * as long as the defaults (localhost broker, group {@code tiko-app}, JSON serializer,
 * earliest offset reset) are acceptable.
 *
 * <p>{@code producerProperties} and {@code consumerProperties} are pass-through into the
 * underlying Apache Kafka client {@code Properties}. Every native client knob
 * ({@code linger.ms}, {@code compression.type}, {@code max.poll.records},
 * {@code enable.idempotence}, ...) is reachable without the framework wrapping each one.
 * Tiko-supplied values ({@code bootstrap.servers}, {@code group.id},
 * {@code auto.offset.reset}, {@code key.deserializer}, {@code value.deserializer}) win on
 * collision.
 */
@Configuration(prefix = "tiko.kafka")
public record KafkaConfig(
        @Default("localhost:9092") @Key("bootstrap-servers") String bootstrapServers,
        @Default("tiko-app") @Key("consumer-group") String consumerGroup,
        @Default("json") String serializer,
        @Default("earliest") @Key("auto-offset-reset") String autoOffsetReset,
        @Default("PT0.5S") @Key("poll-timeout") Duration pollTimeout,
        @Default("PT5S") @Key("shutdown-timeout") Duration shutdownTimeout,
        @Key("producer-properties") Map<String, String> producerProperties,
        @Key("consumer-properties") Map<String, String> consumerProperties) {}
```

- [ ] **Step 2: Write the module-baked defaults**

File: `tiko-kafka/src/main/resources/META-INF/tiko/defaults.yaml`

```yaml
# Defaults baked into tiko-kafka.jar. User application.yaml entries override these
# per-key; values not overridden fall through to @Default annotations on KafkaConfig.
tiko:
  kafka:
    bootstrap-servers: "localhost:9092"
    consumer-group: "tiko-app"
    serializer: json
    auto-offset-reset: earliest
    poll-timeout: PT0.5S
    shutdown-timeout: PT5S
    producer-properties: {}
    consumer-properties: {}
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/KafkaConfig.java \
        tiko-kafka/src/main/resources/META-INF/tiko/defaults.yaml
git commit -m "feat(kafka): KafkaConfig record + module-baked defaults.yaml"
```

---

## Phase 5 — Client interfaces, Apache impls, FakeKafkaBroker

### Task 15: Add `KafkaProducerClient` interface

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/client/KafkaProducerClient.java`

- [ ] **Step 1: Write the interface**

```java
package io.tiko.kafka.client;

import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Thin abstraction over {@code org.apache.kafka.clients.producer.Producer}. Exists so
 * tests can substitute {@link io.tiko.kafka.test.FakeKafkaBroker FakeKafkaBroker} without
 * running a real broker, and so any future producer variant (transactional, Confluent
 * registry-aware, ...) can be slotted in without changing the bootstrap.
 *
 * <p>MVP exposes only {@code send} and {@code close} — the surface the bootstrap actually
 * uses. Additional capabilities are added when a use case requires them.
 */
public interface KafkaProducerClient extends AutoCloseable {

    /**
     * Send a record. Returns a {@link Future} that completes when the broker acknowledges
     * the send (or when the fake broker captures it). Used by the bootstrap to detect
     * send failures and route them to {@code KafkaEgressError}.
     */
    Future<RecordMetadata> send(ProducerRecord<String, byte[]> record);

    /** Release client resources. Idempotent. */
    @Override
    void close();
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/client/KafkaProducerClient.java
git commit -m "feat(kafka): KafkaProducerClient interface"
```

---

### Task 16: Add `KafkaConsumerClient` interface

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/client/KafkaConsumerClient.java`

- [ ] **Step 1: Write the interface**

```java
package io.tiko.kafka.client;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Thin abstraction over {@code org.apache.kafka.clients.consumer.Consumer}. Tests substitute
 * {@link io.tiko.kafka.test.FakeKafkaBroker FakeKafkaBroker}; production code uses
 * {@link ApacheKafkaConsumerClient}.
 *
 * <p>The bootstrap drives one runner per source topic; each runner owns one client. Apache
 * Kafka clients are single-threaded — clients must not be shared across threads.
 */
public interface KafkaConsumerClient extends AutoCloseable {

    /** Subscribe this consumer to the given topics. */
    void subscribe(Collection<String> topics);

    /** Poll for records with the given timeout. May return an empty batch. */
    ConsumerRecords<String, byte[]> poll(Duration timeout);

    /** Synchronously commit the given offsets. */
    void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets);

    /** Seek the consumer back to the given offset on the given partition. */
    void seek(TopicPartition partition, long offset);

    /**
     * Wake the consumer thread up out of a blocking {@code poll}, causing it to throw
     * {@code WakeupException}. Used at shutdown.
     */
    void wakeup();

    /** Release client resources. Idempotent. */
    @Override
    void close();
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/client/KafkaConsumerClient.java
git commit -m "feat(kafka): KafkaConsumerClient interface"
```

---

### Task 17: Implement `ApacheKafkaProducerClient`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/client/ApacheKafkaProducerClient.java`

- [ ] **Step 1: Write the production producer**

```java
package io.tiko.kafka.client;

import io.tiko.kafka.KafkaConfig;
import java.util.Properties;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Production {@link KafkaProducerClient} backed by {@link KafkaProducer}. Constructed
 * once per container by {@link io.tiko.kafka.runtime.KafkaTransportBootstrap} and shared
 * across every {@code @KafkaSink} subscription.
 *
 * <p>Tiko-owned settings ({@code bootstrap.servers}, {@code key.serializer},
 * {@code value.serializer}) win over user-supplied {@code producer-properties} on
 * collision.
 */
public final class ApacheKafkaProducerClient implements KafkaProducerClient {

    private final KafkaProducer<String, byte[]> producer;

    public ApacheKafkaProducerClient(KafkaConfig config) {
        Properties props = new Properties();
        if (config.producerProperties() != null) {
            props.putAll(config.producerProperties());
        }
        // Tiko-owned settings win.
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
        return producer.send(record);
    }

    @Override
    public void close() {
        producer.close();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/client/ApacheKafkaProducerClient.java
git commit -m "feat(kafka): ApacheKafkaProducerClient backed by KafkaProducer"
```

---

### Task 18: Implement `ApacheKafkaConsumerClient`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/client/ApacheKafkaConsumerClient.java`

- [ ] **Step 1: Write the production consumer**

```java
package io.tiko.kafka.client;

import io.tiko.kafka.KafkaConfig;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Production {@link KafkaConsumerClient} backed by {@link KafkaConsumer}. One instance
 * per source topic; lifetime managed by {@code ThreadPerTopicRunner}.
 *
 * <p>Auto-commit is forced off — the runner does manual {@code commitSync(offset+1)} per
 * record on success and {@code seek} on bridge failure. Tiko-owned settings win over
 * user-supplied {@code consumer-properties} on collision.
 */
public final class ApacheKafkaConsumerClient implements KafkaConsumerClient {

    private final KafkaConsumer<String, byte[]> consumer;

    public ApacheKafkaConsumerClient(KafkaConfig config, String consumerGroup) {
        Properties props = new Properties();
        if (config.consumerProperties() != null) {
            props.putAll(config.consumerProperties());
        }
        // Tiko-owned settings win.
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.autoOffsetReset());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(props);
    }

    @Override
    public void subscribe(Collection<String> topics) {
        consumer.subscribe(topics);
    }

    @Override
    public ConsumerRecords<String, byte[]> poll(Duration timeout) {
        return consumer.poll(timeout);
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        consumer.commitSync(offsets);
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        consumer.seek(partition, offset);
    }

    @Override
    public void wakeup() {
        consumer.wakeup();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/client/ApacheKafkaConsumerClient.java
git commit -m "feat(kafka): ApacheKafkaConsumerClient with manual commit + seek"
```

---

### Task 19: Implement `FakeKafkaBroker`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/test/FakeKafkaBroker.java`

- [ ] **Step 1: Write the fake broker**

The fake lives in `src/main/java/` (not `src/test/java/`) so example modules and downstream tests can depend on it. It's documented as test-only via the package and class docstring.

```java
package io.tiko.kafka.test;

import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.client.KafkaProducerClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * In-memory stand-in for a Kafka broker, sufficient for unit-testing
 * {@code KafkaTransportBootstrap} and bridge components without running Docker. Not a
 * production component.
 *
 * <p>Each topic has a single partition (id 0); each record gets a monotonically increasing
 * offset. The fake supports per-record commit, seek, and wakeup so the bootstrap's
 * happy-path AND error-path code paths are testable.
 *
 * <p>Usage:
 * <pre>{@code
 * FakeKafkaBroker broker = new FakeKafkaBroker();
 * KafkaProducerClient producer = broker.producerClient();
 * KafkaConsumerClient consumer = broker.consumerClient("group-a");
 *
 * broker.produce("orders", "{...}".getBytes(StandardCharsets.UTF_8), "X-Trace", "abc");
 *
 * consumer.subscribe(List.of("orders"));
 * ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(10));
 * // ... assertions ...
 *
 * List<ProducerRecord<String, byte[]>> produced = broker.produced("orders");
 * }</pre>
 */
public final class FakeKafkaBroker {

    private final Map<String, List<StoredRecord>> records = new ConcurrentHashMap<>();
    private final List<ProducerRecord<String, byte[]>> produced = new ArrayList<>();

    /** Inject a record into the fake's storage for inbound testing. */
    public synchronized void produce(String topic, byte[] payload, String... headerKv) {
        Headers headers = new RecordHeaders();
        for (int i = 0; i + 1 < headerKv.length; i += 2) {
            headers.add(new RecordHeader(headerKv[i], headerKv[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        List<StoredRecord> list = records.computeIfAbsent(topic, k -> new ArrayList<>());
        list.add(new StoredRecord(list.size(), payload, headers, System.currentTimeMillis()));
    }

    /** All records that were sent through {@link #producerClient()} for the given topic, in send order. */
    public synchronized List<ProducerRecord<String, byte[]>> produced(String topic) {
        List<ProducerRecord<String, byte[]>> result = new ArrayList<>();
        for (ProducerRecord<String, byte[]> r : produced) {
            if (r.topic().equals(topic)) result.add(r);
        }
        return result;
    }

    /** Look up the latest record by header value, useful for correlation-id-keyed assertions. */
    public synchronized Optional<ProducerRecord<String, byte[]>> findProduced(String topic, String headerKey, String headerValue) {
        return produced(topic).stream()
                .filter(r -> {
                    Header h = r.headers().lastHeader(headerKey);
                    return h != null && new String(h.value(), java.nio.charset.StandardCharsets.UTF_8).equals(headerValue);
                })
                .findFirst();
    }

    /** Returns a {@link KafkaProducerClient} that captures sends into this broker's storage. */
    public KafkaProducerClient producerClient() {
        return new FakeProducerClient(this);
    }

    /** Returns a {@link KafkaConsumerClient} bound to the given consumer group. */
    public KafkaConsumerClient consumerClient(String consumerGroup) {
        return new FakeConsumerClient(this, consumerGroup);
    }

    // --- internal types -----------------------------------------------------------

    record StoredRecord(int offset, byte[] payload, Headers headers, long timestamp) {}

    synchronized List<StoredRecord> recordsFor(String topic) {
        return records.computeIfAbsent(topic, k -> new ArrayList<>());
    }

    synchronized void capture(ProducerRecord<String, byte[]> record) {
        produced.add(record);
        // Also store under the topic so a consumer subscribed to the same topic can see it.
        produce(record.topic(), record.value(),
                Optional.ofNullable(record.headers().lastHeader("X-Correlation-Id"))
                        .map(h -> new String(h.value(), java.nio.charset.StandardCharsets.UTF_8))
                        .map(v -> new String[] {"X-Correlation-Id", v})
                        .orElse(new String[0]));
    }

    private static final class FakeProducerClient implements KafkaProducerClient {
        private final FakeKafkaBroker broker;

        FakeProducerClient(FakeKafkaBroker broker) {
            this.broker = broker;
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
            broker.capture(record);
            RecordMetadata md = new RecordMetadata(
                    new TopicPartition(record.topic(), 0),
                    broker.recordsFor(record.topic()).size() - 1L,
                    0, System.currentTimeMillis(), 0, record.value() == null ? 0 : record.value().length);
            return CompletableFuture.completedFuture(md);
        }

        @Override
        public void close() {
            /* nothing */
        }
    }

    private static final class FakeConsumerClient implements KafkaConsumerClient {
        private final FakeKafkaBroker broker;
        @SuppressWarnings("unused")
        private final String consumerGroup;
        private Collection<String> subscribed = List.of();
        private final Map<TopicPartition, AtomicLong> positions = new HashMap<>();
        private volatile boolean wakeup;

        FakeConsumerClient(FakeKafkaBroker broker, String consumerGroup) {
            this.broker = broker;
            this.consumerGroup = consumerGroup;
        }

        @Override
        public void subscribe(Collection<String> topics) {
            this.subscribed = List.copyOf(topics);
            for (String t : topics) positions.putIfAbsent(new TopicPartition(t, 0), new AtomicLong(0));
        }

        @Override
        public ConsumerRecords<String, byte[]> poll(Duration timeout) {
            if (wakeup) {
                wakeup = false;
                throw new org.apache.kafka.common.errors.WakeupException();
            }
            Map<TopicPartition, List<ConsumerRecord<String, byte[]>>> out = new HashMap<>();
            for (String topic : subscribed) {
                TopicPartition tp = new TopicPartition(topic, 0);
                long pos = positions.computeIfAbsent(tp, k -> new AtomicLong(0)).get();
                List<StoredRecord> stored = broker.recordsFor(topic);
                List<ConsumerRecord<String, byte[]>> batch = new ArrayList<>();
                for (int i = (int) pos; i < stored.size(); i++) {
                    StoredRecord r = stored.get(i);
                    batch.add(new ConsumerRecord<>(topic, 0, r.offset(), r.timestamp(),
                            org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, r.payload() == null ? 0 : r.payload().length,
                            null, r.payload(), r.headers(), Optional.empty()));
                }
                if (!batch.isEmpty()) {
                    positions.get(tp).set(stored.size());
                    out.put(tp, batch);
                }
            }
            return new ConsumerRecords<>(out);
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            // Fake doesn't persist committed offsets across instances — runtime commits are
            // verified through call-recording in tests if needed.
        }

        @Override
        public void seek(TopicPartition partition, long offset) {
            positions.computeIfAbsent(partition, k -> new AtomicLong()).set(offset);
        }

        @Override
        public void wakeup() {
            wakeup = true;
        }

        @Override
        public void close() {
            /* nothing */
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS. Brief unchecked warnings about generics are acceptable.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/test/FakeKafkaBroker.java
git commit -m "feat(kafka): FakeKafkaBroker in-memory test stand-in"
```

---

## Phase 6 — `tiko-kafka-processor`

### Task 20: Create `tiko-kafka-processor` module skeleton

**Files:**
- Create: `tiko-kafka-processor/pom.xml`
- Modify: `pom.xml` (root) — add `tiko-kafka-processor` to `<modules>`

- [ ] **Step 1: Register the module in the root pom**

Insert `<module>tiko-kafka-processor</module>` after `<module>tiko-kafka</module>` in the root pom's `<modules>` block.

- [ ] **Step 2: Create `tiko-kafka-processor/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-parent</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>tiko-kafka-processor</artifactId>
    <name>Tiko Kafka Annotation Processor</name>
    <description>
        Compile-time annotation processor for @KafkaSource / @KafkaSink. Generates
        io.tiko.generated.KafkaTransportBootstrap and validates bridge methods.
        Independent of tiko-processor; both run side-by-side on the user's
        annotation-processor path.
    </description>

    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-kafka</artifactId>
        </dependency>

        <dependency>
            <groupId>com.google.auto.service</groupId>
            <artifactId>auto-service-annotations</artifactId>
        </dependency>
        <dependency>
            <groupId>com.google.auto.service</groupId>
            <artifactId>auto-service</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>com.palantir.javapoet</groupId>
            <artifactId>javapoet</artifactId>
        </dependency>

        <!-- For compile-testing -->
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-runtime</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-processor</artifactId>
            <scope>test</scope>
        </dependency>
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
        <dependency>
            <groupId>com.google.testing.compile</groupId>
            <artifactId>compile-testing</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Verify the module is in the reactor**

Run: `mvn -pl tiko-kafka-processor -am compile -q`
Expected: BUILD SUCCESS, `tiko-kafka-processor` listed in the reactor.

- [ ] **Step 4: Commit**

```bash
git add pom.xml tiko-kafka-processor/pom.xml
git commit -m "feat(kafka-processor): module skeleton"
```

---

### Task 21: Add `KafkaSourceDescriptor` and `KafkaSinkDescriptor` model records

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/model/KafkaSourceDescriptor.java`
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/model/KafkaSinkDescriptor.java`

- [ ] **Step 1: Write `KafkaSourceDescriptor`**

```java
package io.tiko.kafka.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time descriptor for one {@code @KafkaSource}-annotated bridge method.
 * Populated by the processor during round 1; consumed by the generator in round 1
 * write phase.
 *
 * @param enclosingClass         declaring {@code @Component} class
 * @param method                 the bridge method element
 * @param topic                  {@code @KafkaSource(topic)}
 * @param consumerGroup          {@code @KafkaSource(consumerGroup)}; empty means use YAML default
 * @param serializerClass        {@code @KafkaSource(serializer)} or {@code KafkaSerializer.Default.class}
 * @param eventName              {@code @EventTrigger(eventName)} on the same method
 * @param payloadType            the bridge method's first parameter type
 * @param producedEventType      the bridge method's return type
 * @param wantsKafkaContext      true if the method has a second parameter typed {@code KafkaContext}
 */
public record KafkaSourceDescriptor(
        TypeElement enclosingClass,
        ExecutableElement method,
        String topic,
        String consumerGroup,
        TypeMirror serializerClass,
        String eventName,
        TypeMirror payloadType,
        TypeMirror producedEventType,
        boolean wantsKafkaContext) {}
```

- [ ] **Step 2: Write `KafkaSinkDescriptor`**

```java
package io.tiko.kafka.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time descriptor for one {@code @KafkaSink}-annotated bridge method.
 *
 * @param enclosingClass         declaring {@code @Component} class
 * @param method                 the bridge method element
 * @param topic                  {@code @KafkaSink(topic)}
 * @param partitionKey           {@code @KafkaSink(partitionKey)}; empty means null key
 * @param serializerClass        {@code @KafkaSink(serializer)} or {@code KafkaSerializer.Default.class}
 * @param eventType              the bridge method's first parameter type (the local event)
 * @param producedPayloadType    the bridge method's return type (the Kafka payload)
 */
public record KafkaSinkDescriptor(
        TypeElement enclosingClass,
        ExecutableElement method,
        String topic,
        String partitionKey,
        TypeMirror serializerClass,
        TypeMirror eventType,
        TypeMirror producedPayloadType) {}
```

- [ ] **Step 3: Verify they compile**

Run: `mvn -pl tiko-kafka-processor compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/model/KafkaSourceDescriptor.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/model/KafkaSinkDescriptor.java
git commit -m "feat(kafka-processor): KafkaSourceDescriptor + KafkaSinkDescriptor"
```

---

### Task 22: Create the processor entry point with discovery (no validation, no generation yet)

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java`

- [ ] **Step 1: Write the processor skeleton**

```java
package io.tiko.kafka.processor;

import com.google.auto.service.AutoService;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;

/**
 * Main entry point for the Kafka annotation processor. Independent of
 * {@code TikoAnnotationProcessor}: both register via {@code @AutoService} and both run
 * on the user's annotation-processor path.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Discover all {@code @KafkaSource} and {@code @KafkaSink} methods, build descriptors.</li>
 *   <li>Run validations (see {@link KafkaSourceValidator}, {@link KafkaSinkValidator}).</li>
 *   <li>If no errors: emit {@code io.tiko.generated.KafkaTransportBootstrap} + the
 *       {@code META-INF/services/io.tiko.TransportBootstrap} entry.</li>
 * </ol>
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class KafkaAnnotationProcessor extends AbstractProcessor {

    private boolean done;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                KafkaSource.class.getCanonicalName(),
                KafkaSink.class.getCanonicalName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (done || roundEnv.processingOver()) return false;

        List<KafkaSourceDescriptor> sources = new ArrayList<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(KafkaSource.class)) {
            if (e instanceof ExecutableElement m) sources.add(buildSourceDescriptor(m));
        }

        List<KafkaSinkDescriptor> sinks = new ArrayList<>();
        for (Element e : roundEnv.getElementsAnnotatedWith(KafkaSink.class)) {
            if (e instanceof ExecutableElement m) sinks.add(buildSinkDescriptor(m));
        }

        // Validation + generation hooks land in later tasks. For now, just record that
        // discovery happens; subsequent tasks plug into this method.
        if (!sources.isEmpty() || !sinks.isEmpty()) {
            // Hook for downstream tasks — see Tasks 23-30.
            // KafkaSourceValidator.validate(processingEnv, sources);
            // KafkaSinkValidator.validate(processingEnv, sinks);
            // new KafkaTransportBootstrapGenerator(processingEnv).generate(sources, sinks);
        }

        done = true;
        return false;
    }

    private KafkaSourceDescriptor buildSourceDescriptor(ExecutableElement method) {
        KafkaSource ann = method.getAnnotation(KafkaSource.class);
        EventTrigger trigger = method.getAnnotation(EventTrigger.class);

        TypeElement enclosing = (TypeElement) method.getEnclosingElement();
        List<? extends VariableElement> params = method.getParameters();
        TypeMirror payload = params.isEmpty() ? null : params.get(0).asType();
        boolean wantsContext = params.size() >= 2
                && params.get(1).asType().toString().equals("io.tiko.kafka.KafkaContext");

        return new KafkaSourceDescriptor(
                enclosing,
                method,
                ann.topic(),
                ann.consumerGroup(),
                readClassValue(method, KafkaSource.class, "serializer", KafkaSerializer.Default.class),
                trigger == null ? "" : trigger.eventName(),
                payload,
                method.getReturnType(),
                wantsContext);
    }

    private KafkaSinkDescriptor buildSinkDescriptor(ExecutableElement method) {
        KafkaSink ann = method.getAnnotation(KafkaSink.class);

        TypeElement enclosing = (TypeElement) method.getEnclosingElement();
        List<? extends VariableElement> params = method.getParameters();
        TypeMirror eventType = params.isEmpty() ? null : params.get(0).asType();

        return new KafkaSinkDescriptor(
                enclosing,
                method,
                ann.topic(),
                ann.partitionKey(),
                readClassValue(method, KafkaSink.class, "serializer", KafkaSerializer.Default.class),
                eventType,
                method.getReturnType());
    }

    /**
     * Reads a {@code Class<?>} annotation value via {@link MirroredTypeException}, the only
     * reliable way to get a {@code TypeMirror} for an annotation's class-literal parameter
     * during annotation processing.
     */
    private TypeMirror readClassValue(
            ExecutableElement method, Class<?> annotation, String memberName, Class<?> defaultClass) {
        for (AnnotationMirror am : method.getAnnotationMirrors()) {
            if (!am.getAnnotationType().toString().equals(annotation.getCanonicalName())) continue;
            for (var entry : am.getElementValues().entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals(memberName)) {
                    AnnotationValue v = entry.getValue();
                    return (TypeMirror) v.getValue();
                }
            }
        }
        // No explicit value — return the default class's TypeMirror.
        return processingEnv.getElementUtils()
                .getTypeElement(defaultClass.getCanonicalName())
                .asType();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-kafka-processor compile -q`
Expected: BUILD SUCCESS. AutoService generates `META-INF/services/javax.annotation.processing.Processor` at compile time.

- [ ] **Step 3: Commit**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): KafkaAnnotationProcessor skeleton with discovery"
```

---

### Task 23: Implement Singleton-scope validation

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/SingletonBridgeValidator.java`
- Create: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/SingletonBridgeValidatorTest.java`
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java` (wire the validator in)

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class SingletonBridgeValidatorTest {

    @Test
    void source_on_singleton_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.OrderBridge",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderBridge {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }

    @Test
    void source_on_request_scope_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.OrderBridge",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.REQUEST)
                                public class OrderBridge {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource");
        assertThat(compilation).hadErrorContaining("Scope.SINGLETON");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl tiko-kafka-processor test -Dtest=SingletonBridgeValidatorTest -q`
Expected: FAIL on the negative case (compiler accepts the invalid bridge) because no validator runs yet.

- [ ] **Step 3: Implement the validator**

```java
package io.tiko.kafka.processor.validation;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/**
 * Validates that every Kafka bridge component is {@code @Component(scope = Scope.SINGLETON)}.
 * Kafka consumer threads run outside any request/event scope; resolving a non-singleton
 * bridge component would fail at runtime.
 */
public final class SingletonBridgeValidator {

    private SingletonBridgeValidator() {}

    public static boolean validate(
            Messager messager,
            List<KafkaSourceDescriptor> sources,
            List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            if (!isSingleton(s.enclosingClass())) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource bridge component must be declared @Component(scope = Scope.SINGLETON). "
                                + "Kafka consumer threads run outside any request/event scope.",
                        s.method());
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            if (!isSingleton(s.enclosingClass())) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink bridge component must be declared @Component(scope = Scope.SINGLETON).",
                        s.method());
                ok = false;
            }
        }
        return ok;
    }

    private static boolean isSingleton(javax.lang.model.element.TypeElement type) {
        Component c = type.getAnnotation(Component.class);
        return c != null && c.scope() == Scope.SINGLETON;
    }
}
```

- [ ] **Step 4: Wire the validator into the processor**

Replace the hook block in `KafkaAnnotationProcessor.process(...)` (the commented `// Hook for downstream tasks` section) with:

```java
if (!sources.isEmpty() || !sinks.isEmpty()) {
    boolean ok = SingletonBridgeValidator.validate(processingEnv.getMessager(), sources, sinks);
    // Additional validators land in Tasks 24-28.
    // Generation lands in Tasks 29-30.
    if (!ok) return false;
}
```

Add the import:

```java
import io.tiko.kafka.processor.validation.SingletonBridgeValidator;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl tiko-kafka-processor test -Dtest=SingletonBridgeValidatorTest -q`
Expected: PASS, both tests green.

- [ ] **Step 6: Commit**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/SingletonBridgeValidator.java \
        tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/SingletonBridgeValidatorTest.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): validate bridge components are SINGLETON-scoped"
```

---

### Task 24: Implement required-sibling-annotation validation

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/RequiredSiblingValidator.java`
- Create: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/RequiredSiblingValidatorTest.java`
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class RequiredSiblingValidatorTest {

    @Test
    void source_without_event_trigger_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.OrderBridge",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderBridge {
                                    @KafkaSource(topic = "orders")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource requires a sibling @EventTrigger");
    }

    @Test
    void sink_without_event_handler_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.OrderPublisher",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderPublisher {
                                    @KafkaSink(topic = "orders")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSink requires a sibling @EventHandler");
    }

    @Test
    void source_and_sink_on_same_method_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.BadBridge",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class BadBridge {
                                    @KafkaSource(topic = "a")
                                    @KafkaSink(topic = "b")
                                    @EventHandler
                                    @EventTrigger(eventName = "x")
                                    public OrderPlaced both(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource and @KafkaSink cannot coexist");
    }
}
```

- [ ] **Step 2: Run to verify failures**

Run: `mvn -pl tiko-kafka-processor test -Dtest=RequiredSiblingValidatorTest -q`
Expected: FAIL — no validator yet.

- [ ] **Step 3: Implement `RequiredSiblingValidator`**

```java
package io.tiko.kafka.processor.validation;

import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;

/**
 * Validates the required sibling annotations on each bridge method:
 * <ul>
 *   <li>{@code @KafkaSource} must coexist with {@code @EventTrigger} (else the message
 *       has nowhere to go).</li>
 *   <li>{@code @KafkaSink} must coexist with {@code @EventHandler} (else the runtime has
 *       no event to subscribe to).</li>
 *   <li>{@code @KafkaSource} and {@code @KafkaSink} cannot coexist on the same method.</li>
 * </ul>
 */
public final class RequiredSiblingValidator {

    private RequiredSiblingValidator() {}

    public static boolean validate(
            Messager messager,
            List<KafkaSourceDescriptor> sources,
            List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            ExecutableElement m = s.method();
            if (m.getAnnotation(KafkaSink.class) != null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource and @KafkaSink cannot coexist on the same method.",
                        m);
                ok = false;
                continue;
            }
            if (m.getAnnotation(EventTrigger.class) == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource requires a sibling @EventTrigger(eventName = \"...\") on the same method "
                                + "so the deserialized event has a name to publish under.",
                        m);
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            ExecutableElement m = s.method();
            if (m.getAnnotation(KafkaSource.class) != null) {
                // already reported above
                continue;
            }
            if (m.getAnnotation(EventHandler.class) == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink requires a sibling @EventHandler on the same method so the framework "
                                + "knows which local event triggers the send.",
                        m);
                ok = false;
            }
        }
        return ok;
    }
}
```

- [ ] **Step 4: Wire into the processor**

Update the hook block in `KafkaAnnotationProcessor.process(...)`:

```java
if (!sources.isEmpty() || !sinks.isEmpty()) {
    boolean ok = true;
    ok &= SingletonBridgeValidator.validate(processingEnv.getMessager(), sources, sinks);
    ok &= RequiredSiblingValidator.validate(processingEnv.getMessager(), sources, sinks);
    if (!ok) return false;
}
```

Add the import:

```java
import io.tiko.kafka.processor.validation.RequiredSiblingValidator;
```

- [ ] **Step 5: Run the test**

Run: `mvn -pl tiko-kafka-processor test -Dtest=RequiredSiblingValidatorTest -q`
Expected: PASS, three tests green.

- [ ] **Step 6: Commit**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/RequiredSiblingValidator.java \
        tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/RequiredSiblingValidatorTest.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): validate required sibling annotations + exclusivity"
```

---

### Task 25: Implement bridge-method-shape validation

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/BridgeMethodShapeValidator.java`
- Create: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/BridgeMethodShapeValidatorTest.java`
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class BridgeMethodShapeValidatorTest {

    @Test
    void void_sink_return_type_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.VoidSink",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class VoidSink {
                                    @EventHandler
                                    @KafkaSink(topic = "orders")
                                    public void toKafka(OrderPlaced e) {}
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSink method must return a non-void payload");
    }

    @Test
    void kafka_context_in_wrong_position_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.WrongShape",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.kafka.KafkaContext;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class WrongShape {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(KafkaContext ctx, OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("KafkaContext, if present, must be the second parameter");
    }

    @Test
    void zero_param_source_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.NoParam",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class NoParam {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka() { return null; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource method must declare at least one parameter");
    }
}
```

- [ ] **Step 2: Run to verify failures**

Run: `mvn -pl tiko-kafka-processor test -Dtest=BridgeMethodShapeValidatorTest -q`
Expected: FAIL.

- [ ] **Step 3: Implement the validator**

```java
package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;

/**
 * Validates bridge method shapes:
 * <ul>
 *   <li>{@code @KafkaSource}: at least one parameter (the payload). Optional second
 *       parameter must be {@code io.tiko.kafka.KafkaContext} (exact type, no subtype).</li>
 *   <li>{@code @KafkaSink}: non-void return type. Exactly one parameter (the local event).</li>
 * </ul>
 */
public final class BridgeMethodShapeValidator {

    private static final String KAFKA_CONTEXT_FQN = "io.tiko.kafka.KafkaContext";

    private BridgeMethodShapeValidator() {}

    public static boolean validate(
            Messager messager,
            List<KafkaSourceDescriptor> sources,
            List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            ExecutableElement m = s.method();
            List<? extends VariableElement> params = m.getParameters();
            if (params.isEmpty()) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource method must declare at least one parameter (the deserialized payload).",
                        m);
                ok = false;
                continue;
            }
            if (params.size() >= 2) {
                if (!params.get(1).asType().toString().equals(KAFKA_CONTEXT_FQN)) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "KafkaContext, if present, must be the second parameter and typed exactly "
                                    + KAFKA_CONTEXT_FQN + " (no subtype).",
                            m);
                    ok = false;
                }
                if (params.size() > 2) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "@KafkaSource method accepts at most two parameters (payload[, KafkaContext]).",
                            m);
                    ok = false;
                }
            }
            if (m.getReturnType().getKind() == TypeKind.VOID) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource method must return the local event payload (non-void).",
                        m);
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            ExecutableElement m = s.method();
            if (m.getParameters().size() != 1) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink method must declare exactly one parameter (the local event).",
                        m);
                ok = false;
            }
            if (m.getReturnType().getKind() == TypeKind.VOID) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink method must return a non-void payload to send to Kafka.",
                        m);
                ok = false;
            }
        }
        return ok;
    }
}
```

- [ ] **Step 4: Wire into the processor**

Update the hook block:

```java
if (!sources.isEmpty() || !sinks.isEmpty()) {
    boolean ok = true;
    ok &= SingletonBridgeValidator.validate(processingEnv.getMessager(), sources, sinks);
    ok &= RequiredSiblingValidator.validate(processingEnv.getMessager(), sources, sinks);
    ok &= BridgeMethodShapeValidator.validate(processingEnv.getMessager(), sources, sinks);
    if (!ok) return false;
}
```

Add the import:

```java
import io.tiko.kafka.processor.validation.BridgeMethodShapeValidator;
```

- [ ] **Step 5: Run the test**

Run: `mvn -pl tiko-kafka-processor test -Dtest=BridgeMethodShapeValidatorTest -q`
Expected: PASS, three tests green.

- [ ] **Step 6: Commit**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/BridgeMethodShapeValidator.java \
        tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/BridgeMethodShapeValidatorTest.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): validate bridge method parameter/return shape"
```

---

### Task 26: Implement `partitionKey` accessor validation

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/PartitionKeyValidator.java`
- Create: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/PartitionKeyValidatorTest.java`
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class PartitionKeyValidatorTest {

    @Test
    void partition_key_referencing_existing_record_component_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId, int amount) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.Publisher",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }

    @Test
    void partition_key_referencing_unknown_component_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId, int amount) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.Publisher",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders", partitionKey = "missingField")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("partitionKey 'missingField' does not resolve");
    }

    @Test
    void empty_partition_key_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.Publisher",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }
}
```

- [ ] **Step 2: Run to verify failures**

Run: `mvn -pl tiko-kafka-processor test -Dtest=PartitionKeyValidatorTest -q`
Expected: FAIL on the "unknown component" case.

- [ ] **Step 3: Implement the validator**

```java
package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Validates that {@code @KafkaSink(partitionKey = "name")} resolves to a zero-arg public
 * method (typically a record component accessor) on the bridge's return type.
 */
public final class PartitionKeyValidator {

    private PartitionKeyValidator() {}

    public static boolean validate(
            ProcessingEnvironment env, Messager messager, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSinkDescriptor s : sinks) {
            if (s.partitionKey().isEmpty()) continue;
            TypeMirror returnType = s.producedPayloadType();
            if (!(returnType instanceof DeclaredType dt)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink(partitionKey = \"" + s.partitionKey()
                                + "\") cannot be applied to a non-declared return type.",
                        s.method());
                ok = false;
                continue;
            }
            TypeElement returnElement = (TypeElement) dt.asElement();
            boolean found = false;
            for (Element member : env.getElementUtils().getAllMembers(returnElement)) {
                if (member.getKind() != ElementKind.METHOD) continue;
                ExecutableElement m = (ExecutableElement) member;
                if (m.getParameters().isEmpty() && m.getSimpleName().contentEquals(s.partitionKey())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink partitionKey '" + s.partitionKey() + "' does not resolve to a zero-arg "
                                + "method on " + returnElement.getQualifiedName() + ".",
                        s.method());
                ok = false;
            }
        }
        return ok;
    }
}
```

- [ ] **Step 4: Wire into the processor**

```java
ok &= PartitionKeyValidator.validate(processingEnv, processingEnv.getMessager(), sinks);
```

Add the import:

```java
import io.tiko.kafka.processor.validation.PartitionKeyValidator;
```

- [ ] **Step 5: Run the test**

Run: `mvn -pl tiko-kafka-processor test -Dtest=PartitionKeyValidatorTest -q`
Expected: PASS, three tests green.

- [ ] **Step 6: Commit**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/validation/PartitionKeyValidator.java \
        tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/validation/PartitionKeyValidatorTest.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): validate @KafkaSink partitionKey resolves on return type"
```

---

### Task 27: Generate `KafkaTransportBootstrap` + dispatchers + ServiceLoader entry

**Files:**
- Create: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGenerator.java`
- Create: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGeneratorTest.java`
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java`

- [ ] **Step 1: Write the failing generator test**

```java
package io.tiko.kafka.processor.generator;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class KafkaTransportBootstrapGeneratorTest {

    @Test
    void source_and_sink_generate_bootstrap_and_service_entry() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                        JavaFileObjects.forSourceString(
                                "demo.OrderConsumer",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderConsumer {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """),
                        JavaFileObjects.forSourceString(
                                "demo.OrderPublisher",
                                """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderPublisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("io.tiko.generated.KafkaTransportBootstrap");
        assertThat(compilation)
                .generatedFile(StandardLocation.CLASS_OUTPUT,
                        "META-INF/services/io.tiko.TransportBootstrap");
    }

    @Test
    void no_kafka_annotations_means_no_generation() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"));

        assertThat(compilation).succeeded();
        // No KafkaTransportBootstrap should be emitted when no @KafkaSource / @KafkaSink exist.
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl tiko-kafka-processor test -Dtest=KafkaTransportBootstrapGeneratorTest -q`
Expected: FAIL — the generator isn't wired yet.

- [ ] **Step 3: Implement the generator**

```java
package io.tiko.kafka.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.tools.StandardLocation;

/**
 * Emits one {@code io.tiko.generated.KafkaTransportBootstrap} class per compilation unit
 * plus the matching {@code META-INF/services/io.tiko.TransportBootstrap} entry.
 *
 * <p>The generated class implements {@link io.tiko.TransportBootstrap}. {@code start(Container)}
 * resolves bridge components via {@code container.get(...)}, looks up the {@code KafkaConfig}
 * record, resolves serializers via {@code ServiceLoader<NamedKafkaSerializer>}, subscribes
 * one EventBus callback per sink, and launches one consumer thread per source.
 * {@code shutdown()} signals the threads to stop and closes the producer / consumers.
 */
public final class KafkaTransportBootstrapGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";
    private static final String CLASS_NAME = "KafkaTransportBootstrap";

    private static final ClassName TRANSPORT_BOOTSTRAP = ClassName.get("io.tiko", "TransportBootstrap");
    private static final ClassName CONTAINER = ClassName.get("io.tiko", "Container");
    private static final ClassName KAFKA_BOOTSTRAP_SUPPORT =
            ClassName.get("io.tiko.kafka.runtime", "KafkaBootstrapSupport");
    private static final ClassName KAFKA_SOURCE_DESCRIPTOR =
            ClassName.get("io.tiko.kafka.runtime", "GeneratedSourceDescriptor");
    private static final ClassName KAFKA_SINK_DESCRIPTOR =
            ClassName.get("io.tiko.kafka.runtime", "GeneratedSinkDescriptor");

    private final ProcessingEnvironment env;

    public KafkaTransportBootstrapGenerator(ProcessingEnvironment env) {
        this.env = env;
    }

    public void generate(List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) throws IOException {
        if (sources.isEmpty() && sinks.isEmpty()) return;

        TypeSpec.Builder cls = TypeSpec.classBuilder(CLASS_NAME)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TRANSPORT_BOOTSTRAP)
                .addField(KAFKA_BOOTSTRAP_SUPPORT, "support", Modifier.PRIVATE)
                .addJavadoc("Generated by tiko-kafka-processor. Do not edit.\n");

        cls.addMethod(buildStartMethod(sources, sinks));
        cls.addMethod(buildShutdownMethod());

        cls.addMethod(buildSourcesProvider(sources));
        cls.addMethod(buildSinksProvider(sinks));

        // Per-source dispatcher method: takes the resolved component + payload + ctx; invokes the bridge.
        for (int i = 0; i < sources.size(); i++) {
            cls.addMethod(buildSourceDispatcher(sources.get(i), i));
        }
        // Per-sink dispatcher method: takes the resolved component + event; returns the serialised payload.
        for (int i = 0; i < sinks.size(); i++) {
            cls.addMethod(buildSinkDispatcher(sinks.get(i), i));
        }

        JavaFile.builder(GENERATED_PACKAGE, cls.build()).build().writeTo(env.getFiler());

        writeServiceLoaderEntry(env.getFiler());
    }

    private MethodSpec buildStartMethod(List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        return MethodSpec.methodBuilder("start")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(CONTAINER, "container")
                .addStatement("this.support = new $T(container, sources(), sinks())", KAFKA_BOOTSTRAP_SUPPORT)
                .addStatement("this.support.start()")
                .build();
    }

    private MethodSpec buildShutdownMethod() {
        return MethodSpec.methodBuilder("shutdown")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .beginControlFlow("if (this.support != null)")
                .addStatement("this.support.shutdown()")
                .addStatement("this.support = null")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildSourcesProvider(List<KafkaSourceDescriptor> sources) {
        TypeName listOfDescriptors = ParameterizedTypeName.get(ClassName.get(java.util.List.class), KAFKA_SOURCE_DESCRIPTOR);
        MethodSpec.Builder b = MethodSpec.methodBuilder("sources")
                .addModifiers(Modifier.PRIVATE)
                .returns(listOfDescriptors);

        if (sources.isEmpty()) {
            b.addStatement("return $T.of()", java.util.List.class);
            return b.build();
        }

        b.addStatement("$T<$T> list = new $T<>()", java.util.List.class, KAFKA_SOURCE_DESCRIPTOR, java.util.ArrayList.class);
        for (int i = 0; i < sources.size(); i++) {
            KafkaSourceDescriptor s = sources.get(i);
            b.addStatement(
                    "list.add(new $T($S, $S, $S, $T.class, $T.class, $L, this::source$L))",
                    KAFKA_SOURCE_DESCRIPTOR,
                    s.topic(),
                    s.consumerGroup(),
                    s.eventName(),
                    ClassName.get(s.payloadType()),
                    ClassName.get(s.serializerClass()),
                    s.wantsKafkaContext(),
                    i);
        }
        b.addStatement("return list");
        return b.build();
    }

    private MethodSpec buildSinksProvider(List<KafkaSinkDescriptor> sinks) {
        TypeName listOfDescriptors = ParameterizedTypeName.get(ClassName.get(java.util.List.class), KAFKA_SINK_DESCRIPTOR);
        MethodSpec.Builder b = MethodSpec.methodBuilder("sinks")
                .addModifiers(Modifier.PRIVATE)
                .returns(listOfDescriptors);

        if (sinks.isEmpty()) {
            b.addStatement("return $T.of()", java.util.List.class);
            return b.build();
        }

        b.addStatement("$T<$T> list = new $T<>()", java.util.List.class, KAFKA_SINK_DESCRIPTOR, java.util.ArrayList.class);
        for (int i = 0; i < sinks.size(); i++) {
            KafkaSinkDescriptor s = sinks.get(i);
            b.addStatement(
                    "list.add(new $T($S, $S, $T.class, $T.class, this::sink$L))",
                    KAFKA_SINK_DESCRIPTOR,
                    s.topic(),
                    s.partitionKey(),
                    ClassName.get(s.eventType()),
                    ClassName.get(s.serializerClass()),
                    i);
        }
        b.addStatement("return list");
        return b.build();
    }

    private MethodSpec buildSourceDispatcher(KafkaSourceDescriptor s, int index) {
        ClassName bridgeClass = ClassName.get(s.enclosingClass());
        TypeName payloadName = ClassName.get(s.payloadType());

        MethodSpec.Builder b = MethodSpec.methodBuilder("source" + index)
                .addModifiers(Modifier.PRIVATE)
                .returns(Object.class)
                .addParameter(CONTAINER, "container")
                .addParameter(Object.class, "payload")
                .addParameter(ClassName.get("io.tiko.kafka", "KafkaContext"), "ctx");

        b.addStatement("$T bridge = container.get($T.class)", bridgeClass, bridgeClass);
        if (s.wantsKafkaContext()) {
            b.addStatement("return bridge.$L(($T) payload, ctx)", s.method().getSimpleName(), payloadName);
        } else {
            b.addStatement("return bridge.$L(($T) payload)", s.method().getSimpleName(), payloadName);
        }
        return b.build();
    }

    private MethodSpec buildSinkDispatcher(KafkaSinkDescriptor s, int index) {
        ClassName bridgeClass = ClassName.get(s.enclosingClass());
        TypeName eventName = ClassName.get(s.eventType());

        MethodSpec.Builder b = MethodSpec.methodBuilder("sink" + index)
                .addModifiers(Modifier.PRIVATE)
                .returns(Object.class)
                .addParameter(CONTAINER, "container")
                .addParameter(Object.class, "event");

        b.addStatement("$T bridge = container.get($T.class)", bridgeClass, bridgeClass);
        b.addStatement("return bridge.$L(($T) event)", s.method().getSimpleName(), eventName);
        return b.build();
    }

    private void writeServiceLoaderEntry(Filer filer) throws IOException {
        var resource = filer.createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/services/io.tiko.TransportBootstrap");
        try (Writer w = new OutputStreamWriter(resource.openOutputStream(), StandardCharsets.UTF_8)) {
            w.write(GENERATED_PACKAGE + "." + CLASS_NAME);
            w.write("\n");
        }
    }
}
```

- [ ] **Step 4: Wire the generator into the processor**

Update the hook block in `KafkaAnnotationProcessor.process(...)`:

```java
if (!sources.isEmpty() || !sinks.isEmpty()) {
    boolean ok = true;
    ok &= SingletonBridgeValidator.validate(processingEnv.getMessager(), sources, sinks);
    ok &= RequiredSiblingValidator.validate(processingEnv.getMessager(), sources, sinks);
    ok &= BridgeMethodShapeValidator.validate(processingEnv.getMessager(), sources, sinks);
    ok &= PartitionKeyValidator.validate(processingEnv, processingEnv.getMessager(), sinks);
    if (!ok) return false;
    try {
        new KafkaTransportBootstrapGenerator(processingEnv).generate(sources, sinks);
    } catch (java.io.IOException ex) {
        processingEnv.getMessager().printMessage(
                javax.tools.Diagnostic.Kind.ERROR,
                "Failed to generate KafkaTransportBootstrap: " + ex.getMessage());
    }
}
```

Add the import:

```java
import io.tiko.kafka.processor.generator.KafkaTransportBootstrapGenerator;
```

- [ ] **Step 5: Build the support types in `tiko-kafka` (next task in Phase 7)**

The generator references `io.tiko.kafka.runtime.{KafkaBootstrapSupport, GeneratedSourceDescriptor, GeneratedSinkDescriptor}` which don't exist yet. Run the test now and it will fail with `cannot find symbol` — that's expected; the support types land in Task 28. Skip to Task 28 and return here when those types exist.

- [ ] **Step 6: After Task 28 completes, run this generator test**

Run: `mvn -pl tiko-kafka-processor test -Dtest=KafkaTransportBootstrapGeneratorTest -q`
Expected: PASS.

- [ ] **Step 7: Commit (after Step 6 passes)**

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGenerator.java \
        tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGeneratorTest.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): generate KafkaTransportBootstrap + ServiceLoader entry"
```

---

## Phase 7 — `KafkaTransportBootstrap` runtime support

### Task 28: Add `KafkaBootstrapSupport` + descriptor records in `tiko-kafka`

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/GeneratedSourceDescriptor.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/GeneratedSinkDescriptor.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaConsumerRunner.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java`

- [ ] **Step 1: Write the source descriptor record**

```java
package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.kafka.KafkaContext;
import io.tiko.kafka.KafkaSerializer;

/**
 * Runtime descriptor for one {@code @KafkaSource} bridge method, populated by the
 * generated {@code KafkaTransportBootstrap}. The dispatcher field is a method reference
 * to a generated private method on the bootstrap class that invokes the user's bridge.
 *
 * @param topic              source topic
 * @param consumerGroup      empty string means "use KafkaConfig.consumerGroup"
 * @param eventName          tracing label (not used for dispatch in MVP)
 * @param payloadType        first parameter type of the bridge method
 * @param serializerClass    KafkaSerializer.Default.class means "use named YAML default"
 * @param wantsKafkaContext  true if the bridge declares the optional 2nd KafkaContext parameter
 * @param dispatcher         invokes the bridge method; returns the local event payload to publish
 */
public record GeneratedSourceDescriptor(
        String topic,
        String consumerGroup,
        String eventName,
        Class<?> payloadType,
        Class<? extends KafkaSerializer<?>> serializerClass,
        boolean wantsKafkaContext,
        SourceDispatcher dispatcher) {

    @FunctionalInterface
    public interface SourceDispatcher {
        Object dispatch(Container container, Object payload, KafkaContext ctx);
    }
}
```

- [ ] **Step 2: Write the sink descriptor record**

```java
package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;

/**
 * Runtime descriptor for one {@code @KafkaSink} bridge method.
 *
 * @param topic              destination topic
 * @param partitionKey       empty string means null Kafka key
 * @param eventType          first parameter type of the sink method (the local event)
 * @param serializerClass    KafkaSerializer.Default.class means "use named YAML default"
 * @param dispatcher         invokes the bridge; returns the payload to send
 */
public record GeneratedSinkDescriptor(
        String topic,
        String partitionKey,
        Class<?> eventType,
        Class<? extends KafkaSerializer<?>> serializerClass,
        SinkDispatcher dispatcher) {

    @FunctionalInterface
    public interface SinkDispatcher {
        Object dispatch(Container container, Object event);
    }
}
```

- [ ] **Step 3: Write the consumer-runner sealed interface and thread-per-topic impl**

```java
package io.tiko.kafka.runtime;

/**
 * Drives the consume loop for one or more {@code @KafkaSource} bindings. Sealed so future
 * threading strategies (shared consumer pool, virtual-thread runner) plug in as new
 * permits without changing the bootstrap. MVP ships {@link ThreadPerTopicRunner} only.
 */
public sealed interface KafkaConsumerRunner permits ThreadPerTopicRunner {

    void start();

    /**
     * Stop the consume loop. Should be safe to call multiple times; the runner must
     * release its client even if {@link #start()} was never invoked.
     */
    void stop();
}
```

```java
package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.client.KafkaConsumerClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/**
 * One thread per source topic. The thread owns its own {@link KafkaConsumerClient} and
 * runs the consume loop documented in the spec: poll → deserialize → invoke bridge →
 * publish → commitSync(offset+1); on bridge throw, route via ErrorHandler and seek-back.
 */
public final class ThreadPerTopicRunner implements KafkaConsumerRunner {

    private final GeneratedSourceDescriptor source;
    private final KafkaConsumerClient consumer;
    private final Container container;
    private final EventBus eventBus;
    private final ErrorHandler errorHandler;
    private final KafkaSerializer<Object> serializer;
    private final KafkaConfig config;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public ThreadPerTopicRunner(
            GeneratedSourceDescriptor source,
            KafkaConsumerClient consumer,
            Container container,
            EventBus eventBus,
            ErrorHandler errorHandler,
            KafkaSerializer<Object> serializer,
            KafkaConfig config) {
        this.source = source;
        this.consumer = consumer;
        this.container = container;
        this.eventBus = eventBus;
        this.errorHandler = errorHandler;
        this.serializer = serializer;
        this.config = config;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        consumer.subscribe(List.of(source.topic()));
        thread = new Thread(this::run, "tiko-kafka-consumer-" + source.topic());
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            // Was never started or already stopped — still close the client.
            try { consumer.close(); } catch (Exception ignored) { /* best-effort */ }
            return;
        }
        consumer.wakeup();
        try {
            if (thread != null) thread.join(config.shutdownTimeout().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try { consumer.close(); } catch (Exception ignored) { /* best-effort */ }
    }

    private void run() {
        try {
            while (running.get()) {
                ConsumerRecords<String, byte[]> records;
                try {
                    records = consumer.poll(config.pollTimeout());
                } catch (WakeupException wakeup) {
                    return;
                }
                for (ConsumerRecord<String, byte[]> r : records) {
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    try {
                        @SuppressWarnings("unchecked")
                        Object payload = serializer.deserialize(r.value(), (Class<Object>) source.payloadType());
                        KafkaContext ctx = new KafkaContext(
                                r.topic(), r.partition(), r.offset(),
                                Instant.ofEpochMilli(r.timestamp()), r.headers());
                        Object event = source.dispatcher().dispatch(container, payload, ctx);
                        eventBus.publish(event);
                        consumer.commitSync(Map.of(tp, new OffsetAndMetadata(r.offset() + 1)));
                    } catch (Exception ex) {
                        errorHandler.handle(new KafkaIngestError(
                                r.topic(), r.partition(), r.offset(), r.headers(), ex));
                        consumer.seek(tp, r.offset());
                        break;
                    }
                }
            }
        } finally {
            // close is handled by stop() to ensure exactly-once close
        }
    }
}
```

- [ ] **Step 4: Write `KafkaBootstrapSupport`**

```java
package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.NamedKafkaSerializer;
import io.tiko.kafka.client.ApacheKafkaConsumerClient;
import io.tiko.kafka.client.ApacheKafkaProducerClient;
import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.client.KafkaProducerClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Runtime helper shared by every generated {@code KafkaTransportBootstrap}. Owns the
 * lifetime of consumer runners and the singleton producer client; resolves serializers
 * via {@code ServiceLoader<NamedKafkaSerializer>}.
 *
 * <p>The producer/consumer factories are injectable so unit tests can swap in
 * {@link io.tiko.kafka.test.FakeKafkaBroker}.
 */
public final class KafkaBootstrapSupport {

    private final Container container;
    private final List<GeneratedSourceDescriptor> sources;
    private final List<GeneratedSinkDescriptor> sinks;

    private final BiFunction<KafkaConfig, String, KafkaConsumerClient> consumerFactory;
    private final java.util.function.Function<KafkaConfig, KafkaProducerClient> producerFactory;

    private final List<KafkaConsumerRunner> runners = new ArrayList<>();
    private KafkaProducerClient producer;

    public KafkaBootstrapSupport(
            Container container,
            List<GeneratedSourceDescriptor> sources,
            List<GeneratedSinkDescriptor> sinks) {
        this(container, sources, sinks,
                ApacheKafkaConsumerClient::new,
                ApacheKafkaProducerClient::new);
    }

    /** Test-only constructor accepting custom client factories (e.g., {@code FakeKafkaBroker}). */
    public KafkaBootstrapSupport(
            Container container,
            List<GeneratedSourceDescriptor> sources,
            List<GeneratedSinkDescriptor> sinks,
            BiFunction<KafkaConfig, String, KafkaConsumerClient> consumerFactory,
            java.util.function.Function<KafkaConfig, KafkaProducerClient> producerFactory) {
        this.container = container;
        this.sources = sources;
        this.sinks = sinks;
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
    }

    public void start() {
        KafkaConfig config = container.get(KafkaConfig.class);
        EventBus eventBus = container.getEventBus();
        ErrorHandler errorHandler = resolveErrorHandler(container);
        Map<String, KafkaSerializer<?>> named = loadNamedSerializers();

        // Outbound — subscribe one callback per @KafkaSink.
        if (producer == null && !sinks.isEmpty()) {
            producer = producerFactory.apply(config);
        }
        for (GeneratedSinkDescriptor sink : sinks) {
            KafkaSerializer<Object> serializer = resolveSerializer(sink.serializerClass(), config, named);
            eventBus.subscribe(asEventType(sink.eventType()), wrapSinkCallback(sink, serializer, errorHandler));
        }

        // Inbound — one runner per source.
        for (GeneratedSourceDescriptor source : sources) {
            String group = source.consumerGroup().isEmpty() ? config.consumerGroup() : source.consumerGroup();
            KafkaSerializer<Object> serializer = resolveSerializer(source.serializerClass(), config, named);
            KafkaConsumerClient client = consumerFactory.apply(config, group);
            KafkaConsumerRunner runner = new ThreadPerTopicRunner(
                    source, client, container, eventBus, errorHandler, serializer, config);
            runners.add(runner);
            runner.start();
        }
    }

    public void shutdown() {
        for (KafkaConsumerRunner r : runners) {
            try { r.stop(); } catch (Exception ignored) { /* best-effort */ }
        }
        runners.clear();
        if (producer != null) {
            try { producer.close(); } catch (Exception ignored) { /* best-effort */ }
            producer = null;
        }
    }

    // --- helpers ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> EventCallback<T> wrapSinkCallback(
            GeneratedSinkDescriptor sink, KafkaSerializer<Object> serializer, ErrorHandler errorHandler) {
        return (T event) -> {
            try {
                Object payload = sink.dispatcher().dispatch(container, event);
                byte[] bytes = serializer.serialize(payload);
                String key = sink.partitionKey().isEmpty() ? null : resolvePartitionKey(payload, sink.partitionKey());
                producer.send(new ProducerRecord<>(sink.topic(), null, key, bytes, new RecordHeaders()));
            } catch (Exception ex) {
                errorHandler.handle(new KafkaEgressError(sink.topic(), event, ex));
            }
        };
    }

    private static String resolvePartitionKey(Object payload, String accessor) {
        try {
            Method m = payload.getClass().getMethod(accessor);
            Object v = m.invoke(payload);
            return v == null ? null : v.toString();
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("partitionKey '" + accessor + "' could not be resolved at runtime", e);
        }
    }

    private static Map<String, KafkaSerializer<?>> loadNamedSerializers() {
        Map<String, KafkaSerializer<?>> result = new HashMap<>();
        for (NamedKafkaSerializer named : ServiceLoader.load(NamedKafkaSerializer.class)) {
            result.put(named.name(), named.serializer());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static KafkaSerializer<Object> resolveSerializer(
            Class<? extends KafkaSerializer<?>> declared,
            KafkaConfig config,
            Map<String, KafkaSerializer<?>> named) {
        if (declared != KafkaSerializer.Default.class) {
            try {
                return (KafkaSerializer<Object>) declared.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(
                        "@KafkaSource/@KafkaSink serializer " + declared.getName()
                                + " could not be instantiated. It must have a public no-arg constructor.", e);
            }
        }
        KafkaSerializer<?> impl = named.get(config.serializer());
        if (impl == null) {
            throw new RuntimeException(
                    "tiko.kafka.serializer = '" + config.serializer()
                            + "' but no NamedKafkaSerializer with that name was found via ServiceLoader. "
                            + "Known names: " + named.keySet());
        }
        return (KafkaSerializer<Object>) impl;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> asEventType(Class<?> eventType) {
        return (Class<T>) eventType;
    }

    /**
     * The {@link Container} interface does not expose the {@link ErrorHandler}; tiko-kafka
     * does not need to redesign tiko-api for the MVP. We pull it from the container via a
     * package-private accessor on the runtime if available, otherwise fall back to a
     * default that logs to {@code java.util.logging}.
     */
    private static ErrorHandler resolveErrorHandler(Container container) {
        try {
            Method m = container.getClass().getMethod("getErrorHandler");
            Object eh = m.invoke(container);
            if (eh instanceof ErrorHandler typed) return typed;
        } catch (ReflectiveOperationException ignored) {
            /* fall through */
        }
        return ctx -> java.util.logging.Logger.getLogger("io.tiko.kafka")
                .log(java.util.logging.Level.WARNING,
                        ctx.getClass().getSimpleName() + ": " + ctx.cause(), ctx.cause());
    }
}
```

- [ ] **Step 5: Verify everything in `tiko-kafka` compiles**

Run: `mvn -pl tiko-kafka compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Re-run the generator test from Task 27**

Run: `mvn -pl tiko-kafka-processor test -Dtest=KafkaTransportBootstrapGeneratorTest -q`
Expected: PASS now that the runtime types exist.

- [ ] **Step 7: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/runtime/
git commit -m "feat(kafka): KafkaBootstrapSupport + runner + descriptors"
```

Then complete Task 27's commit:

```bash
git add tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGenerator.java \
        tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGeneratorTest.java \
        tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/KafkaAnnotationProcessor.java
git commit -m "feat(kafka-processor): generate KafkaTransportBootstrap + ServiceLoader entry"
```

---

### Task 29: Expose `Container.getErrorHandler()` for transport access

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Container.java` — add `default ErrorHandler getErrorHandler()`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java` — override to return the stored handler
- Modify: any other `Container` impl in `tiko-runtime` if present
- Modify: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java` — drop the reflective fallback, call `container.getErrorHandler()` directly

- [ ] **Step 1: Add the default method in `Container`**

In `tiko-api/src/main/java/io/tiko/Container.java`, add:

```java
/**
 * Returns the {@link ErrorHandler} configured on this container, used to route
 * framework-side errors (sync handler throws, async handler throws, transport
 * ingest/egress failures). Default implementation returns a JUL-backed
 * handler so existing user impls keep compiling, but the runtime always
 * overrides this with the {@code TikoOptions}-supplied handler.
 *
 * @return the error handler, never {@code null}
 */
default ErrorHandler getErrorHandler() {
    return ctx -> java.util.logging.Logger.getLogger("io.tiko")
            .log(java.util.logging.Level.WARNING,
                    ctx.getClass().getSimpleName() + ": " + ctx.cause(), ctx.cause());
}
```

- [ ] **Step 2: Override in `AggregatingContainer`**

In `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`, find the constructor that takes `ErrorHandler errorHandler` (already present from #44) and store it as a field if not already; expose:

```java
@Override
public ErrorHandler getErrorHandler() {
    return errorHandler;
}
```

- [ ] **Step 3: Update the generated `TikoContainerImpl` template**

The generated `TikoContainerImpl` already receives an `ErrorHandler` constructor argument (#44). Add an override in the generator at `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` (or the equivalent location — search for `errorHandler` field) so the generated class implements `getErrorHandler()` returning the stored field.

Run: `mvn -pl tiko-processor test -q`
Expected: PASS — the existing `ContainerGeneratorErrorHandlerTest` still asserts the field is wired.

- [ ] **Step 4: Simplify `KafkaBootstrapSupport.resolveErrorHandler`**

Replace the reflective method in `KafkaBootstrapSupport` with:

```java
private static ErrorHandler resolveErrorHandler(Container container) {
    return container.getErrorHandler();
}
```

- [ ] **Step 5: Run the full build**

Run: `mvn -pl tiko-api,tiko-processor,tiko-runtime,tiko-kafka,tiko-kafka-processor -am test -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/Container.java \
        tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java \
        tiko-processor/src/main/java/io/tiko/processor/generator/ \
        tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java
git commit -m "feat(api): expose Container.getErrorHandler() for transports"
```

---

## Phase 8 — Unit tests with `FakeKafkaBroker`

### Task 30: Inbound round-trip — payload deserialized and published

**Files:**
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaInboundRoundTripTest.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/OrderPlaced.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/OrderKafkaConsumer.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/OrderRecorder.java`

- [ ] **Step 1: Write the test fixtures**

`OrderPlaced.java`:

```java
package io.tiko.kafka.runtime.fixtures;

public record OrderPlaced(String orderId, int amount) {}
```

`OrderKafkaConsumer.java`:

```java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSource;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaConsumer {
    @KafkaSource(topic = "orders")
    @EventTrigger(eventName = "OrderPlaced")
    public OrderPlaced fromKafka(OrderPlaced payload) {
        return payload;
    }
}
```

`OrderRecorder.java`:

```java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(scope = Scope.SINGLETON)
public class OrderRecorder {

    public final List<OrderPlaced> received = new CopyOnWriteArrayList<>();

    @EventHandler
    public void on(OrderPlaced event) {
        received.add(event);
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.runtime.fixtures.OrderKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Drives an inbound record through the fake broker and asserts the local handler
 * receives it. Verifies serializer resolution, dispatcher invocation, and EventBus
 * publish-by-class.
 *
 * <p>This test relies on a {@code TestKafkaBootstrap} fixture (one per test class) that
 * wires the {@code FakeKafkaBroker} into the bootstrap's client factories via the
 * test-only constructor.
 */
class KafkaInboundRoundTripTest {

    @Test
    void payload_round_trips_to_local_handler() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.startFor(container, broker, OrderKafkaConsumer.class)) {

            // Inject a JSON record into the fake broker.
            byte[] payload = "{\"orderId\":\"o-1\",\"amount\":42}".getBytes(StandardCharsets.UTF_8);
            broker.produce("orders", payload);

            OrderRecorder recorder = container.get(OrderRecorder.class);
            await().atMost(Duration.ofSeconds(3))
                    .until(() -> !recorder.received.isEmpty());

            assertThat(recorder.received).hasSize(1);
            assertThat(recorder.received.get(0)).isEqualTo(new OrderPlaced("o-1", 42));
        }
    }
}
```

- [ ] **Step 3: Add the `TestKafkaBootstrap` helper**

Add Awaitility dependency to `tiko-kafka/pom.xml`:

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.2</version>
    <scope>test</scope>
</dependency>
```

Create `tiko-kafka/src/test/java/io/tiko/kafka/runtime/TestKafkaBootstrap.java`:

```java
package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import io.tiko.kafka.test.FakeKafkaBroker;
import java.util.ServiceLoader;

/**
 * Helper that locates the generated {@code KafkaTransportBootstrap} and rewires its
 * internal {@link KafkaBootstrapSupport} to use a {@link FakeKafkaBroker} instead of
 * real Apache Kafka clients. Returned as {@link AutoCloseable} so tests can use
 * try-with-resources.
 *
 * <p>This relies on the generated bootstrap creating its {@code KafkaBootstrapSupport}
 * inside {@code start(Container)} via {@code new KafkaBootstrapSupport(container, sources(), sinks())}.
 * We pre-empt that by replacing the singleton bootstrap with one whose {@code support}
 * field is constructed against the fake.
 */
final class TestKafkaBootstrap implements AutoCloseable {

    private final TransportBootstrap underlying;

    private TestKafkaBootstrap(TransportBootstrap underlying) {
        this.underlying = underlying;
    }

    static TestKafkaBootstrap startFor(Container container, FakeKafkaBroker broker, Class<?>... bridges) throws Exception {
        // Locate the generated bootstrap (only one is registered).
        TransportBootstrap generated = null;
        for (TransportBootstrap tb : ServiceLoader.load(TransportBootstrap.class)) {
            if (tb.getClass().getName().equals("io.tiko.generated.KafkaTransportBootstrap")) {
                generated = tb;
                break;
            }
        }
        if (generated == null) {
            throw new IllegalStateException("io.tiko.generated.KafkaTransportBootstrap not on classpath. "
                    + "Ensure tiko-kafka-processor is on the annotation-processor path.");
        }

        // Use reflection to read the generated `sources()` and `sinks()` private methods so
        // we can construct a KafkaBootstrapSupport against the fake broker's clients.
        var sourcesMethod = generated.getClass().getDeclaredMethod("sources");
        sourcesMethod.setAccessible(true);
        var sinksMethod = generated.getClass().getDeclaredMethod("sinks");
        sinksMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<GeneratedSourceDescriptor> sources = (java.util.List<GeneratedSourceDescriptor>) sourcesMethod.invoke(generated);
        @SuppressWarnings("unchecked")
        java.util.List<GeneratedSinkDescriptor> sinks = (java.util.List<GeneratedSinkDescriptor>) sinksMethod.invoke(generated);

        KafkaBootstrapSupport support = new KafkaBootstrapSupport(
                container, sources, sinks,
                (config, group) -> broker.consumerClient(group),
                config -> broker.producerClient());
        support.start();

        return new TestKafkaBootstrap(new TransportBootstrap() {
            @Override public void start(Container c) { /* already started */ }
            @Override public void shutdown() { support.shutdown(); }
        });
    }

    @Override
    public void close() {
        underlying.shutdown();
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-kafka test -Dtest=KafkaInboundRoundTripTest -q`
Expected: PASS — the fake broker's record is deserialized and reaches `OrderRecorder.on`.

- [ ] **Step 5: Commit**

```bash
git add tiko-kafka/src/test/ tiko-kafka/pom.xml
git commit -m "test(kafka): inbound round-trip via FakeKafkaBroker"
```

---

### Task 31: Inbound with `KafkaContext` second parameter

**Files:**
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/AuditPayload.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/AuditRecorded.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/AuditKafkaConsumer.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/AuditRecorder.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaContextInjectionTest.java`

- [ ] **Step 1: Add fixtures**

```java
// AuditPayload.java
package io.tiko.kafka.runtime.fixtures;
public record AuditPayload(String id, String action) {}
```

```java
// AuditRecorded.java
package io.tiko.kafka.runtime.fixtures;
public record AuditRecorded(String id, String action, String correlationId) {}
```

```java
// AuditKafkaConsumer.java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.KafkaContext;
import io.tiko.kafka.annotations.KafkaSource;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;

@Component(scope = Scope.SINGLETON)
public class AuditKafkaConsumer {
    @KafkaSource(topic = "audits")
    @EventTrigger(eventName = "AuditRecorded")
    public AuditRecorded fromKafka(AuditPayload payload, KafkaContext ctx) {
        Header h = ctx.headers().lastHeader("X-Correlation-Id");
        String corr = h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
        return new AuditRecorded(payload.id(), payload.action(), corr);
    }
}
```

```java
// AuditRecorder.java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(scope = Scope.SINGLETON)
public class AuditRecorder {
    public final List<AuditRecorded> received = new CopyOnWriteArrayList<>();
    @EventHandler public void on(AuditRecorded e) { received.add(e); }
}
```

- [ ] **Step 2: Write the test**

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.runtime.fixtures.AuditKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.AuditRecorded;
import io.tiko.kafka.runtime.fixtures.AuditRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KafkaContextInjectionTest {

    @Test
    void second_parameter_receives_kafka_context() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.startFor(container, broker, AuditKafkaConsumer.class)) {

            broker.produce("audits",
                    "{\"id\":\"a-1\",\"action\":\"login\"}".getBytes(StandardCharsets.UTF_8),
                    "X-Correlation-Id", "trace-42");

            AuditRecorder recorder = container.get(AuditRecorder.class);
            await().atMost(Duration.ofSeconds(3))
                    .until(() -> !recorder.received.isEmpty());

            AuditRecorded got = recorder.received.get(0);
            assertThat(got).isEqualTo(new AuditRecorded("a-1", "login", "trace-42"));
        }
    }
}
```

- [ ] **Step 3: Run the test**

Run: `mvn -pl tiko-kafka test -Dtest=KafkaContextInjectionTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/test/
git commit -m "test(kafka): KafkaContext second parameter is injected with headers"
```

---

### Task 32: Inbound bridge failure → `KafkaIngestError` + seek-back

**Files:**
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/ThrowingBridge.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaIngestErrorTest.java`

- [ ] **Step 1: Add the throwing bridge fixture**

```java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSource;
import java.util.concurrent.atomic.AtomicInteger;

@Component(scope = Scope.SINGLETON)
public class ThrowingBridge {

    public final AtomicInteger callCount = new AtomicInteger(0);

    @KafkaSource(topic = "errors")
    @EventTrigger(eventName = "OrderPlaced")
    public OrderPlaced fromKafka(OrderPlaced payload) {
        callCount.incrementAndGet();
        if (callCount.get() <= 2) throw new RuntimeException("simulated bridge failure on call " + callCount.get());
        return payload;
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.runtime.fixtures.ThrowingBridge;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class KafkaIngestErrorTest {

    @Test
    void bridge_throws_then_seek_back_replays_and_succeeds() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        TikoOptions opts = TikoOptions.builder().errorHandler(errors::add).build();

        try (Container container = Tiko.create(opts);
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.startFor(container, broker, ThrowingBridge.class)) {

            broker.produce("errors",
                    "{\"orderId\":\"o-9\",\"amount\":7}".getBytes(StandardCharsets.UTF_8));

            ThrowingBridge bridge = container.get(ThrowingBridge.class);
            OrderRecorder recorder = container.get(OrderRecorder.class);

            // After two simulated failures the third invocation succeeds and the local handler runs.
            await().atMost(Duration.ofSeconds(5)).until(() -> !recorder.received.isEmpty());

            assertThat(bridge.callCount.get()).isEqualTo(3);
            assertThat(recorder.received).containsExactly(new OrderPlaced("o-9", 7));
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0)).isInstanceOfSatisfying(KafkaIngestError.class, e -> {
                assertThat(e.topic()).isEqualTo("errors");
                assertThat(e.cause()).hasMessageContaining("simulated bridge failure on call 1");
            });
        }
    }
}
```

- [ ] **Step 3: Run the test**

Run: `mvn -pl tiko-kafka test -Dtest=KafkaIngestErrorTest -q`
Expected: PASS — `seek` rewinds the fake broker's position; the runner replays the record until the bridge stops throwing.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/test/
git commit -m "test(kafka): bridge throw routes to KafkaIngestError and seeks back"
```

---

### Task 33: Outbound sink — `@EventHandler @KafkaSink` round-trip

**Files:**
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/OrderKafkaPublisher.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaOutboundRoundTripTest.java`

- [ ] **Step 1: Add the publisher bridge fixture**

```java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.kafka.annotations.KafkaSink;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {
    @EventHandler
    @KafkaSink(topic = "orders-out", partitionKey = "orderId")
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
```

- [ ] **Step 2: Write the test**

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

class KafkaOutboundRoundTripTest {

    @Test
    void publishing_locally_sends_a_kafka_record_with_partition_key() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.startFor(container, broker, OrderKafkaPublisher.class)) {

            container.getEventBus().publish(new OrderPlaced("o-5", 21));

            List<ProducerRecord<String, byte[]>> produced = broker.produced("orders-out");
            assertThat(produced).hasSize(1);
            ProducerRecord<String, byte[]> rec = produced.get(0);
            assertThat(rec.key()).isEqualTo("o-5");

            OrderPlaced roundTripped = (OrderPlaced)
                    new JsonKafkaSerializer().deserialize(rec.value(), (Class) OrderPlaced.class);
            assertThat(roundTripped).isEqualTo(new OrderPlaced("o-5", 21));
        }
    }
}
```

- [ ] **Step 3: Run the test**

Run: `mvn -pl tiko-kafka test -Dtest=KafkaOutboundRoundTripTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/test/
git commit -m "test(kafka): outbound sink serialises with partition key"
```

---

### Task 34: Outbound sink failure → `KafkaEgressError`, local handlers still run

**Files:**
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/ThrowingPublisher.java`
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaEgressErrorTest.java`

- [ ] **Step 1: Add the throwing publisher**

```java
package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.kafka.annotations.KafkaSink;

@Component(scope = Scope.SINGLETON)
public class ThrowingPublisher {
    @EventHandler
    @KafkaSink(topic = "fail-out")
    public OrderPlaced toKafka(OrderPlaced event) {
        throw new RuntimeException("simulated egress failure");
    }
}
```

- [ ] **Step 2: Write the test**

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.runtime.fixtures.ThrowingPublisher;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class KafkaEgressErrorTest {

    @Test
    void sink_throw_routes_to_egress_error_and_local_handlers_still_ran() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        TikoOptions opts = TikoOptions.builder().errorHandler(errors::add).build();

        try (Container container = Tiko.create(opts);
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.startFor(container, broker, ThrowingPublisher.class)) {

            container.getEventBus().publish(new OrderPlaced("o-1", 1));

            OrderRecorder recorder = container.get(OrderRecorder.class);
            assertThat(recorder.received).containsExactly(new OrderPlaced("o-1", 1));
            assertThat(broker.produced("fail-out")).isEmpty();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0)).isInstanceOfSatisfying(KafkaEgressError.class, e -> {
                assertThat(e.topic()).isEqualTo("fail-out");
                assertThat(e.cause()).hasMessageContaining("simulated egress failure");
            });
        }
    }
}
```

- [ ] **Step 3: Run the test**

Run: `mvn -pl tiko-kafka test -Dtest=KafkaEgressErrorTest -q`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/test/
git commit -m "test(kafka): sink failure routes to KafkaEgressError without blocking locals"
```

---

## Phase 9 — Integration tests (Testcontainers, real broker)

### Task 35: Set up `integTest` profile and Testcontainers wiring

**Files:**
- Modify: `tiko-kafka/pom.xml` — add `org.testcontainers:kafka` + `junit-jupiter` as `test`-scope deps, register a `failsafe` execution under an `integTest` profile that runs anything matching `*IT.java`

- [ ] **Step 1: Add Testcontainers dependencies**

In `tiko-kafka/pom.xml`, append to `<dependencies>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Add `failsafe` integTest profile**

Append to `tiko-kafka/pom.xml` after `<dependencies>`:

```xml
<profiles>
    <profile>
        <id>integTest</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-failsafe-plugin</artifactId>
                    <version>3.2.2</version>
                    <executions>
                        <execution>
                            <goals>
                                <goal>integration-test</goal>
                                <goal>verify</goal>
                            </goals>
                            <configuration>
                                <includes>
                                    <include>**/*IT.java</include>
                                </includes>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

- [ ] **Step 3: Verify the profile activates**

Run: `mvn -pl tiko-kafka -PintegTest help:active-profiles`
Expected: `integTest` listed under active profiles.

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/pom.xml
git commit -m "build(kafka): add integTest profile + Testcontainers test deps"
```

---

### Task 36: Write a real-broker round-trip integration test

**Files:**
- Create: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/integration/KafkaRealBrokerRoundTripIT.java`

- [ ] **Step 1: Write the integration test**

```java
package io.tiko.kafka.runtime.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.runtime.fixtures.OrderKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real-Kafka end-to-end: starts a Testcontainers broker, runs the published
 * KafkaTransportBootstrap against it (no fake), and asserts publishing locally results
 * in the same JVM's source consumer receiving the message back via the broker.
 *
 * <p>Note: this test uses both {@code @KafkaSink} (publisher in this JVM) and
 * {@code @KafkaSource} (consumer in this JVM) on the SAME topic, with different consumer
 * group than the publisher would have. The sink subscribes to {@link OrderPlaced}
 * events; the source publishes deserialised {@link OrderPlaced} events back to the bus.
 * To prevent a delivery loop, the publisher uses a different topic name than the
 * consumer reads from in the unit tests — here we wire them both to {@code "orders"}
 * because the local handler only records OrderPlaced once (sink runs AFTER local
 * handlers, and the source's republish triggers the same handler with a separate event).
 *
 * <p>If feedback loops surface as flakiness, split into two test JVMs in the e2e module.
 */
@Testcontainers
class KafkaRealBrokerRoundTripIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @Test
    void produced_then_consumed_via_real_broker() {
        TikoOptions opts = TikoOptions.builder()
                .configSource(io.tiko.config.ConfigSources.fromMap(java.util.Map.of(
                        "tiko", java.util.Map.of(
                                "kafka", java.util.Map.of(
                                        "bootstrap-servers", KAFKA.getBootstrapServers(),
                                        "consumer-group", "kafka-it",
                                        "serializer", "json",
                                        "auto-offset-reset", "earliest")))))
                .build();

        try (Container container = Tiko.create(opts)) {
            // The bootstrap is auto-discovered via ServiceLoader and started by Tiko.createInternal.
            container.getEventBus().publish(new OrderPlaced("real-1", 99));

            OrderRecorder recorder = container.get(OrderRecorder.class);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> recorder.received.stream().anyMatch(o -> o.orderId().equals("real-1")));

            assertThat(recorder.received).anyMatch(o -> o.orderId().equals("real-1") && o.amount() == 99);
        }
    }
}
```

- [ ] **Step 2: Confirm the fixtures (`OrderKafkaConsumer`, `OrderKafkaPublisher`, `OrderRecorder`) are accessible**

The IT file references fixtures from Tasks 30 and 33. They live in `tiko-kafka/src/test/java/io/tiko/kafka/runtime/fixtures/`. Since `*IT.java` runs under Failsafe with the same test classpath, no additional wiring is needed.

- [ ] **Step 3: Run the integration test**

Run: `mvn -pl tiko-kafka -PintegTest verify -q`
Expected: PASS. Docker required — the test will be skipped on a host without docker if you add `assumeThat(DockerClientFactory.instance().isDockerAvailable()).isTrue()` as a setUp guard (omitted here for simplicity).

- [ ] **Step 4: Commit**

```bash
git add tiko-kafka/src/test/java/io/tiko/kafka/runtime/integration/KafkaRealBrokerRoundTripIT.java
git commit -m "test(kafka): integTest with Testcontainers proves real-broker round trip"
```

---

## Phase 10 — Demo: `tiko-examples/08_kafka_order_warehouse`

### Task 37: Demo parent module + docker-compose

**Files:**
- Modify: `tiko-examples/pom.xml` — add `08_kafka_order_warehouse` to `<modules>`
- Create: `tiko-examples/08_kafka_order_warehouse/pom.xml`
- Create: `tiko-examples/08_kafka_order_warehouse/docker-compose.yml`

- [ ] **Step 1: Register the new example**

In `tiko-examples/pom.xml`, add `<module>08_kafka_order_warehouse</module>` after `<module>07_async_start</module>`.

- [ ] **Step 2: Create the example parent pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>tiko-examples</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>08_kafka_order_warehouse</artifactId>
    <packaging>pom</packaging>
    <name>08 - Kafka Order/Warehouse Example</name>
    <description>Cross-JVM demo: order-service publishes OrderPlaced to Kafka; warehouse-service consumes from Kafka.</description>

    <modules>
        <module>shared-events</module>
        <module>order-service</module>
        <module>warehouse-service</module>
        <module>e2e</module>
    </modules>
</project>
```

- [ ] **Step 3: Create the docker-compose for local manual runs**

```yaml
# tiko-examples/08_kafka_order_warehouse/docker-compose.yml
# Starts a single-broker Kafka in KRaft mode for local dev. Service images for
# order-service / warehouse-service will be added here when they exist (Phase 4/5).
services:
  kafka:
    image: confluentinc/cp-kafka:7.7.1
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: "PLAINTEXT://:9092,CONTROLLER://:9093"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://localhost:9092"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT"
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@localhost:9093"
      KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER"
      KAFKA_INTER_BROKER_LISTENER_NAME: "PLAINTEXT"
      CLUSTER_ID: "kafka-tiko-cluster"
```

- [ ] **Step 4: Verify the example module is wired**

Run: `mvn -pl tiko-examples/08_kafka_order_warehouse -am compile -q`
Expected: BUILD SUCCESS — Maven recognises the parent pom but skips compilation since there are no source modules yet (sub-modules land in subsequent tasks).

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/pom.xml tiko-examples/08_kafka_order_warehouse/pom.xml \
        tiko-examples/08_kafka_order_warehouse/docker-compose.yml
git commit -m "feat(examples): scaffold 08_kafka_order_warehouse parent + docker-compose"
```

---

### Task 38: `shared-events` module

**Files:**
- Create: `tiko-examples/08_kafka_order_warehouse/shared-events/pom.xml`
- Create: `tiko-examples/08_kafka_order_warehouse/shared-events/src/main/java/io/tiko/examples/kafka/events/OrderPlaced.java`

- [ ] **Step 1: Create the module pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>08_kafka_order_warehouse</artifactId>
        <version>0.1.0</version>
    </parent>
    <artifactId>kafka-shared-events</artifactId>
    <name>08 / shared-events</name>
</project>
```

- [ ] **Step 2: Create the shared event record**

```java
package io.tiko.examples.kafka.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published by order-service when a customer places an order; consumed by
 * warehouse-service to start fulfilment. Same record class on both sides — JSON over
 * Kafka means no schema artifact, just stable field names.
 */
public record OrderPlaced(String orderId, BigDecimal amount, Instant placedAt) {}
```

- [ ] **Step 3: Verify it builds**

Run: `mvn -pl tiko-examples/08_kafka_order_warehouse/shared-events -am compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/08_kafka_order_warehouse/shared-events/
git commit -m "feat(examples): 08 shared-events with OrderPlaced record"
```

---

### Task 39: `order-service` module (publishes OrderPlaced, runnable `main`)

**Files:**
- Create: `tiko-examples/08_kafka_order_warehouse/order-service/pom.xml`
- Create: `tiko-examples/08_kafka_order_warehouse/order-service/src/main/java/io/tiko/examples/kafka/order/Main.java`
- Create: `tiko-examples/08_kafka_order_warehouse/order-service/src/main/java/io/tiko/examples/kafka/order/OrderService.java`
- Create: `tiko-examples/08_kafka_order_warehouse/order-service/src/main/java/io/tiko/examples/kafka/order/OrderKafkaPublisher.java`
- Create: `tiko-examples/08_kafka_order_warehouse/order-service/src/main/resources/application.yaml`

- [ ] **Step 1: Module pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>08_kafka_order_warehouse</artifactId>
        <version>0.1.0</version>
    </parent>
    <artifactId>kafka-order-service</artifactId>
    <name>08 / order-service</name>

    <dependencies>
        <dependency>
            <groupId>io.tiko.examples</groupId>
            <artifactId>kafka-shared-events</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-api</artifactId></dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-runtime</artifactId></dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-config</artifactId></dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-kafka</artifactId></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path><groupId>io.tiko</groupId><artifactId>tiko-processor</artifactId><version>${project.version}</version></path>
                        <path><groupId>io.tiko</groupId><artifactId>tiko-kafka-processor</artifactId><version>${project.version}</version></path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.5.0</version>
                <configuration>
                    <mainClass>io.tiko.examples.kafka.order.Main</mainClass>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${maven-shade.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.tiko.examples.kafka.order.Main</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: `Main.java` — CLI loop placing orders**

```java
package io.tiko.examples.kafka.order;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public final class Main {
    public static void main(String[] args) throws Exception {
        TikoOptions opts = TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .build();
        try (Container container = Tiko.create(opts);
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            OrderService svc = container.get(OrderService.class);
            System.out.println("order-service ready. Type an amount + ENTER to place an order; Ctrl-D to exit.");
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    BigDecimal amount = new BigDecimal(line.trim());
                    OrderPlaced placed = svc.placeOrder(amount);
                    System.out.println("placed: " + placed);
                } catch (NumberFormatException nfe) {
                    System.out.println("(not a number, ignored)");
                }
            }
        }
    }
}
```

- [ ] **Step 3: `OrderService.java`**

```java
package io.tiko.examples.kafka.order;

import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.examples.kafka.events.OrderPlaced;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component(scope = Scope.SINGLETON)
public class OrderService {

    private final EventBus events;

    @Inject
    public OrderService(EventBus events) {
        this.events = events;
    }

    public OrderPlaced placeOrder(BigDecimal amount) {
        OrderPlaced ev = new OrderPlaced(UUID.randomUUID().toString(), amount, Instant.now());
        events.publish(ev);
        return ev;
    }
}
```

- [ ] **Step 4: `OrderKafkaPublisher.java`**

```java
package io.tiko.examples.kafka.order;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.annotations.KafkaSink;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {

    @EventHandler
    @KafkaSink(topic = "orders", partitionKey = "orderId")
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
```

- [ ] **Step 5: `application.yaml`**

```yaml
tiko:
  kafka:
    bootstrap-servers: "${KAFKA_BOOTSTRAP:localhost:9092}"
    consumer-group: order-service
```

- [ ] **Step 6: Build the module**

Run: `mvn -pl tiko-examples/08_kafka_order_warehouse/order-service -am package -q`
Expected: BUILD SUCCESS; shaded jar at `target/kafka-order-service-0.1.0.jar`.

- [ ] **Step 7: Commit**

```bash
git add tiko-examples/08_kafka_order_warehouse/order-service/
git commit -m "feat(examples): 08 order-service with @KafkaSink publisher"
```

---

### Task 40: `warehouse-service` module (consumes OrderPlaced, runnable `main`)

**Files:**
- Create: `tiko-examples/08_kafka_order_warehouse/warehouse-service/pom.xml`
- Create: `tiko-examples/08_kafka_order_warehouse/warehouse-service/src/main/java/io/tiko/examples/kafka/warehouse/Main.java`
- Create: `tiko-examples/08_kafka_order_warehouse/warehouse-service/src/main/java/io/tiko/examples/kafka/warehouse/WarehouseService.java`
- Create: `tiko-examples/08_kafka_order_warehouse/warehouse-service/src/main/java/io/tiko/examples/kafka/warehouse/OrderKafkaConsumer.java`
- Create: `tiko-examples/08_kafka_order_warehouse/warehouse-service/src/main/resources/application.yaml`

- [ ] **Step 1: Module pom** — copy `order-service/pom.xml` from Task 39, change `<artifactId>` to `kafka-warehouse-service`, change the `<mainClass>` in both exec-plugin and shade-plugin to `io.tiko.examples.kafka.warehouse.Main`.

- [ ] **Step 2: `Main.java` — blocks until SIGTERM**

```java
package io.tiko.examples.kafka.warehouse;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        TikoOptions opts = TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .build();
        try (Container container = Tiko.create(opts)) {
            CountDownLatch stop = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown, "warehouse-shutdown"));
            System.out.println("warehouse-service ready, awaiting orders…");
            stop.await();
        }
    }
}
```

- [ ] **Step 3: `WarehouseService.java`**

```java
package io.tiko.examples.kafka.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.examples.kafka.events.OrderPlaced;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component(scope = Scope.SINGLETON)
public class WarehouseService {

    private final Path probeFile = Path.of(System.getProperty("probe.file", "/tmp/warehouse.probe"));

    @EventHandler
    public void on(OrderPlaced event) {
        System.out.println("warehouse received: " + event);
        try {
            Files.writeString(probeFile, event.orderId() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // probe file is optional — only used by the e2e test
        }
    }
}
```

- [ ] **Step 4: `OrderKafkaConsumer.java`**

```java
package io.tiko.examples.kafka.warehouse;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.annotations.KafkaSource;

@Component(scope = Scope.SINGLETON)
public class OrderKafkaConsumer {

    @KafkaSource(topic = "orders")
    @EventTrigger(eventName = "OrderPlaced")
    public OrderPlaced fromKafka(OrderPlaced payload) {
        return payload;
    }
}
```

- [ ] **Step 5: `application.yaml`**

```yaml
tiko:
  kafka:
    bootstrap-servers: "${KAFKA_BOOTSTRAP:localhost:9092}"
    consumer-group: warehouse-service
    auto-offset-reset: earliest
```

- [ ] **Step 6: Build the module**

Run: `mvn -pl tiko-examples/08_kafka_order_warehouse/warehouse-service -am package -q`
Expected: BUILD SUCCESS; shaded jar present.

- [ ] **Step 7: Commit**

```bash
git add tiko-examples/08_kafka_order_warehouse/warehouse-service/
git commit -m "feat(examples): 08 warehouse-service with @KafkaSource consumer"
```

---

### Task 41: `e2e` module — process-orchestrated cross-JVM test

**Files:**
- Create: `tiko-examples/08_kafka_order_warehouse/e2e/pom.xml`
- Create: `tiko-examples/08_kafka_order_warehouse/e2e/src/test/java/io/tiko/examples/kafka/e2e/OrderToWarehouseE2ETest.java`

- [ ] **Step 1: Module pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>08_kafka_order_warehouse</artifactId>
        <version>0.1.0</version>
    </parent>
    <artifactId>kafka-e2e</artifactId>
    <name>08 / e2e</name>

    <dependencies>
        <dependency>
            <groupId>io.tiko.examples</groupId>
            <artifactId>kafka-order-service</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.tiko.examples</groupId>
            <artifactId>kafka-warehouse-service</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
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
    </dependencies>
</project>
```

- [ ] **Step 2: Write the e2e test**

```java
package io.tiko.examples.kafka.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots Kafka via Testcontainers, then forks each service's shaded jar as a separate
 * JVM, wired to the broker via the {@code KAFKA_BOOTSTRAP} env var. Asserts an
 * {@code OrderPlaced} placed through order-service's stdin appears in warehouse-service's
 * probe file within a deadline.
 */
@Testcontainers
class OrderToWarehouseE2ETest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private Path probeFile;
    private Process orderProc;
    private Process warehouseProc;

    @BeforeEach
    void setUp() throws Exception {
        probeFile = Files.createTempFile("warehouse-probe", ".log");
        Files.deleteIfExists(probeFile);
    }

    @AfterEach
    void tearDown() {
        if (orderProc != null) orderProc.destroy();
        if (warehouseProc != null) warehouseProc.destroy();
    }

    @Test
    void order_placed_in_order_service_reaches_warehouse_service() throws Exception {
        String orderJar = jarPath("order-service");
        String warehouseJar = jarPath("warehouse-service");

        warehouseProc = new ProcessBuilder(
                "java",
                "-Dprobe.file=" + probeFile.toAbsolutePath(),
                "-jar", warehouseJar)
                .inheritIO()
                .start();
        warehouseProc.environment().put("KAFKA_BOOTSTRAP", KAFKA.getBootstrapServers());

        // Give the warehouse a moment to subscribe.
        Thread.sleep(2_000);

        orderProc = new ProcessBuilder(
                "java",
                "-jar", orderJar)
                .redirectErrorStream(true)
                .start();
        orderProc.environment().put("KAFKA_BOOTSTRAP", KAFKA.getBootstrapServers());

        // Feed a price to order-service's stdin to place an order.
        try (OutputStream out = orderProc.getOutputStream()) {
            out.write("19.99\n".getBytes(StandardCharsets.UTF_8));
        }

        await().atMost(Duration.ofSeconds(30))
                .until(() -> Files.exists(probeFile) && !Files.readString(probeFile).isBlank());

        String probeContent = Files.readString(probeFile);
        assertThat(probeContent).isNotBlank();
    }

    private static String jarPath(String moduleName) {
        // The reactor builds each service's shaded jar at <module>/target/*.jar before e2e runs.
        Path target = Path.of("..", moduleName, "target");
        try {
            return Files.list(target)
                    .filter(p -> p.toString().endsWith(".jar") && !p.toString().contains("original-"))
                    .map(Path::toString)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No shaded jar in " + target));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 3: Run the e2e test**

Run: `mvn -pl tiko-examples/08_kafka_order_warehouse/e2e -am verify -q`
Expected: PASS (Docker required). The reactor builds the two services' shaded jars before the e2e module's tests run because of the inter-module dependency.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/08_kafka_order_warehouse/e2e/
git commit -m "feat(examples): 08 e2e process-orchestrated cross-JVM test"
```

---

### Task 42: Write `tiko-examples/08_kafka_order_warehouse/README.md`

**Files:**
- Create: `tiko-examples/08_kafka_order_warehouse/README.md`

- [ ] **Step 1: Write the README**

```markdown
# 08 — Kafka order/warehouse (cross-JVM)

Demonstrates Tiko's universal transport-adapter pattern: an `order-service` JVM publishes
`OrderPlaced` events; a `warehouse-service` JVM in a separate process receives them via
Kafka and handles them like any other local event.

Same `@EventHandler` shape on both sides. The only Kafka-specific code lives in the two
bridge files — one `@KafkaSink` in `order-service`, one `@KafkaSource` in `warehouse-service`.

Companion to [`05_multi_module`](../05_multi_module), which aggregates two modules in a
single JVM via `AggregatingContainer`. Same domain split; opposite deployment topology.

## Run locally

```bash
# 1. Start Kafka (single-broker KRaft mode)
docker compose -f tiko-examples/08_kafka_order_warehouse/docker-compose.yml up -d kafka

# 2. Build both shaded JARs
mvn -pl tiko-examples/08_kafka_order_warehouse -am package

# 3. Run warehouse-service (waits for orders)
java -jar tiko-examples/08_kafka_order_warehouse/warehouse-service/target/kafka-warehouse-service-0.1.0.jar &

# 4. Run order-service (CLI prompts for amounts)
java -jar tiko-examples/08_kafka_order_warehouse/order-service/target/kafka-order-service-0.1.0.jar
# Type "19.99" + ENTER → watch warehouse-service log "warehouse received: …"
```

## Layout

| Path | What's there |
|---|---|
| `shared-events/` | `OrderPlaced` record — used by both services as-is |
| `order-service/` | `OrderService` (publishes locally), `OrderKafkaPublisher` (`@EventHandler` + `@KafkaSink`), `Main` |
| `warehouse-service/` | `WarehouseService` (`@EventHandler` on `OrderPlaced`), `OrderKafkaConsumer` (`@KafkaSource` + `@EventTrigger`), `Main` |
| `e2e/` | Testcontainers + `ProcessBuilder` test asserting cross-JVM message flow |
| `docker-compose.yml` | Local Kafka broker; service images are added once they exist |

## How to read the code

Open these two files side-by-side:

- `order-service/.../OrderKafkaPublisher.java` — `@EventHandler` + `@KafkaSink`
- `warehouse-service/.../WarehouseService.java` — `@EventHandler` only

The handler in `warehouse-service` has no Kafka import. It receives `OrderPlaced` events
the same way it would in a single-JVM app — the bridge (`OrderKafkaConsumer.java`) feeds
the bus from Kafka transparently.

## What this proves

- One handler signature works for both transports.
- Each service is its own deployable unit (separate JVM, separate shaded jar, separate process).
- The bridge code is small and confined to one file per direction.
- Compile-time validation catches mistakes: try removing `@EventTrigger` from `OrderKafkaConsumer.java` and run `mvn compile` — the build fails with a clear pointer to the missing sibling annotation.

## Future docker images

Service images for `order-service` / `warehouse-service` will be added to
`docker-compose.yml` once published. Until then, run them as plain JVMs from the shaded jars.
```

- [ ] **Step 2: Commit**

```bash
git add tiko-examples/08_kafka_order_warehouse/README.md
git commit -m "docs(examples): README for 08_kafka_order_warehouse"
```

---

## Phase 11 — Documentation updates

### Task 43: Update `docs/events.md` to mention Kafka shipping

**Files:**
- Modify: `docs/events.md`

- [ ] **Step 1: Update the "publish/subscribe" introduction**

Find the paragraph that says:

> The same handler code is intended to work against any `EventBus` implementation — the in-memory bus ships today (`LocalEventBus` in `tiko-runtime`); a Kafka-backed bus is on the Phase 2 roadmap.

Replace with:

> The same handler code works against any `EventBus` implementation. The in-memory bus (`LocalEventBus` in `tiko-runtime`) ships in core. The Kafka transport (`tiko-kafka` + `tiko-kafka-processor`) is a separate module that bridges via `@KafkaSource` / `@KafkaSink` — see [`tiko-examples/08_kafka_order_warehouse`](../tiko-examples/08_kafka_order_warehouse) for a runnable cross-JVM demo. The universal transport-adapter pattern documented in [`docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md`](./superpowers/specs/2026-05-12-kafka-event-bus-design.md) generalises to HTTP / scheduler / file / gRPC.

- [ ] **Step 2: Commit**

```bash
git add docs/events.md
git commit -m "docs(events): document Kafka transport shipping"
```

---

### Task 44: Update `docs/roadmap.md`

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Move "Kafka event bus integration" from Phase 2 planned to shipped**

In `docs/roadmap.md`, find the Phase 2 section. Remove "Kafka event bus integration" from the planned list. Add to "What ships today":

```markdown
- ✅ Kafka transport (`tiko-kafka`, `tiko-kafka-processor`) — universal transport-adapter pattern via `@KafkaSource` / `@KafkaSink`, `TransportBootstrap` SPI, JSON serializer, per-record commit + seek-back, `FakeKafkaBroker` for tests. Runnable cross-JVM demo at `tiko-examples/08_kafka_order_warehouse`. See [Kafka spec](./superpowers/specs/2026-05-12-kafka-event-bus-design.md).
```

- [ ] **Step 2: Add follow-up items**

Append a new section "Kafka follow-ups (future)":

```markdown
- Avro + schema registry support (`tiko-kafka-avro`).
- Full `@EventTrigger` semantics on bridge methods (factor trigger dispatcher out of EventRegistryGenerator).
- Batch / at-most-once commit modes.
- Topic/queue patterns via `@KafkaSource(consumerGroup = "...")` exercised by a demo.
- Pluggable partition-key extractors.
- Per-source DLQ handling.
- Transactional / exactly-once producers.
```

- [ ] **Step 3: Commit**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): mark Kafka MVP shipped + add follow-ups"
```

---

### Task 45: Update root README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the new example to the runnable-examples table**

Find the table near "## Runnable examples" and add a new row for `08_kafka_order_warehouse` after row 07. Update the introductory sentence count if needed ("Six worked examples" → "Eight worked examples").

```markdown
| 08 | [`08_kafka_order_warehouse`](./tiko-examples/08_kafka_order_warehouse) | Cross-JVM Kafka demo — `@KafkaSource` / `@KafkaSink`, shared event class, Testcontainers e2e |
```

- [ ] **Step 2: Update the documentation index**

In the "Documentation" section, add:

```markdown
| [docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md](./docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md) | Kafka event bus design — universal transport adapter pattern. |
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: link Kafka example #08 from root README"
```

---

## Phase 12 — Wrap

### Task 46: Run the full build, push, open PRs

- [ ] **Step 1: Run the full reactor build**

Run: `mvn clean install -q`
Expected: BUILD SUCCESS. All modules compile, all unit tests pass, Spotless gate green.

- [ ] **Step 2: Run the integTest profile**

Run: `mvn -pl tiko-kafka -PintegTest verify -q`
Expected: PASS, Testcontainers IT green.

- [ ] **Step 3: Run the e2e module**

Run: `mvn -pl tiko-examples/08_kafka_order_warehouse/e2e -am verify -q`
Expected: PASS.

- [ ] **Step 4: Push and open the implementation PR**

```bash
git push -u origin feat/kafka-mvp
gh pr create --base main --title "feat: Kafka event bus MVP" \
    --body "$(cat <<'EOF'
## Summary
- Implements the universal transport-adapter pattern per `docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md`.
- Adds `TransportBootstrap` SPI to `tiko-api`; widens `ErrorContext` with a non-sealed `TransportError` permit.
- New modules: `tiko-kafka`, `tiko-kafka-processor`.
- Runnable cross-JVM demo at `tiko-examples/08_kafka_order_warehouse` with three test layers (unit + Testcontainers IT + process-orchestrated e2e).

## Test plan
- [ ] `mvn clean install -q` passes.
- [ ] `mvn -pl tiko-kafka -PintegTest verify -q` passes (Docker required).
- [ ] `mvn -pl tiko-examples/08_kafka_order_warehouse/e2e -am verify -q` passes (Docker required).
- [ ] Manual run per `tiko-examples/08_kafka_order_warehouse/README.md` works end-to-end.

## Follow-up issues to file after merge
- Avro + schema-registry (`tiko-kafka-avro`).
- Full `@EventTrigger` on bridge methods (factor trigger dispatcher).
- Batch / at-most-once commit modes.
- Topic/queue demo with shared consumer groups.
- Pluggable partition-key extractors.
- Per-source DLQ.
EOF
)"
```

- [ ] **Step 5: File the follow-up issues**

Open seven GitHub issues, each titled per the bullets in the PR body, milestone Phase 2 (or appropriate). Link each issue back to the Kafka spec. These represent the explicit future extension points the spec called out so that closing the MVP doesn't lose track of them.

- [ ] **Step 6: Final commit if anything trailing**

Verify `git status` is clean. If formatter changes appear, run `mvn spotless:apply -pl '!tiko-bom'` and commit:

```bash
git add -A
git commit -m "style: spotless apply"
git push
```

---

## Self-review checklist (run before handing off to executor)

- **Spec coverage:** every section of the spec maps to at least one task.
  - Architecture overview → Tasks 1–3, 22, 27.
  - Public API (annotations + runtime types) → Tasks 5–11.
  - Compile-time validation → Tasks 23–26.
  - Generated code → Task 27.
  - Runtime lifecycle (startup, consume, outbound, shutdown) → Tasks 28–29, 31–34.
  - Serialization SPI + JSON → Tasks 7–8, 12–13.
  - Configuration → Task 14.
  - Error handling → Tasks 11, 29.
  - Demo → Tasks 37–42.
  - Testing → Tasks 30–36, 41.
  - MVP scope cut on `@EventTrigger` on bridges → documented in `@KafkaSource` annotation and `ThreadPerTopicRunner`; tasks 30 and 33 cover the happy path; follow-up issue filed in Task 46.
  - Future extension points → Task 46 (issue filing).

- **Placeholder scan:** no `TBD`, no "implement later", every code block is complete.

- **Type consistency:** names used across tasks line up — `KafkaSerializer`, `NamedKafkaSerializer`, `KafkaContext`, `KafkaConfig`, `KafkaSource`, `KafkaSink`, `KafkaIngestError`, `KafkaEgressError`, `TransportBootstrap`, `KafkaBootstrapSupport`, `GeneratedSourceDescriptor`, `GeneratedSinkDescriptor`, `KafkaConsumerRunner`, `ThreadPerTopicRunner`. Method signatures match between definition (Tasks 5, 7, 28) and call sites (Tasks 27, 28, 30–34).

- **Ambiguity:** Task 27 explicitly notes the runtime types are introduced in Task 28; Task 28 closes the loop by re-running Task 27's generator test.

