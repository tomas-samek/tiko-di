# HTTP + Javalin integration example — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `tiko-examples/09_http_javalin/` — a runnable example module showing how Tiko integrates with an embedded Javalin HTTP server, demonstrating the sync request→response path vs the event-bus side-effects dichotomy.

**Architecture:** A single Maven module. Tiko `Container` runs alongside an embedded Javalin app in one JVM. A small `TikoJavalin.scoped(...)` `Handler` decorator opens a Tiko request scope around every matched route — including body parsing, the bridge call, event publishing, and response serialization. All Tiko APIs used are public; no new framework code.

**Tech Stack:** Java 21, Tiko (api / runtime / processor at compile time), Javalin 6.x for HTTP, Jackson (Javalin default) for JSON, JUnit 5 + AssertJ + `java.net.http.HttpClient` for tests, Maven Shade for the runnable jar.

**Spec:** `docs/superpowers/specs/2026-05-14-http-javalin-integration-example-design.md`. Read it before starting — this plan is the *how*; the spec is the *why*.

---

## Files this plan creates or modifies

**Created — `tiko-examples/09_http_javalin/` (new module):**

| Path | Responsibility |
|---|---|
| `pom.xml` | Module config: deps on tiko-api/runtime/processor, javalin, junit, assertj. Shade plugin for runnable jar. |
| `src/main/java/io/tiko/examples/http/Main.java` | Bootstrap: build container, fetch bridge, build Javalin, register routes via `TikoJavalin.scoped`, start, shutdown hook. |
| `src/main/java/io/tiko/examples/http/TikoJavalin.java` | One static method `scoped(Container, Handler)` wrapping a Javalin handler in `runInRequestScope`. The "middleware" piece. |
| `src/main/java/io/tiko/examples/http/Ticket.java` | Domain record: `(UUID id, String title, Instant createdAt)`. |
| `src/main/java/io/tiko/examples/http/CreateTicketRequest.java` | DTO record: `(String title)`. |
| `src/main/java/io/tiko/examples/http/TicketCreated.java` | Event record: `(UUID id, String title, String requestId, Instant createdAt)`. |
| `src/main/java/io/tiko/examples/http/TicketService.java` | SINGLETON `@Component`: in-memory `ConcurrentHashMap<UUID, Ticket>` with `create` + `find`. |
| `src/main/java/io/tiko/examples/http/RequestId.java` | Interface: `String value()`. |
| `src/main/java/io/tiko/examples/http/RequestIdImpl.java` | REQUEST-scoped `@Component` implementing `RequestId`. Generates UUID at construction. |
| `src/main/java/io/tiko/examples/http/RequestTimer.java` | SINGLETON `@Component`: `@EventHandler` on `RequestStartedEvent` / `RequestEndingEvent`, logs duration. |
| `src/main/java/io/tiko/examples/http/AuditLogger.java` | SINGLETON `@Component`: sync `@EventHandler` on `TicketCreated`, writes to stdout. |
| `src/main/java/io/tiko/examples/http/MetricsCounter.java` | SINGLETON `@Component`: sync `@EventHandler` on `TicketCreated`, increments `AtomicLong`, exposes `count()`. |
| `src/main/java/io/tiko/examples/http/NotificationSender.java` | SINGLETON `@Component`: `@EventHandler(async = true)` on `TicketCreated`, decrements a `CountDownLatch`. |
| `src/main/java/io/tiko/examples/http/TicketHttpRoutes.java` | Plain class (the bridge); not a `@Component` because it needs `EventBus`/`Container`, neither of which are DI-injectable. `Main` constructs one instance after container bootstrap. Two `handle*` methods, straight-line code; no `runInRequestScope` (the decorator handles it). |
| `src/test/java/io/tiko/examples/http/TicketServiceTest.java` | Unit test for the in-memory store (no container). |
| `src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java` | Integration test: real container + real Javalin on random port, real HTTP via `java.net.http.HttpClient`. |
| `README.md` | Module-level docs walking through the dichotomy. |

**Modified:**

| Path | Change |
|---|---|
| `tiko-examples/pom.xml` | Add `<module>09_http_javalin</module>`. |
| `pom.xml` (root) | Add `javalin.version` property + `javalin` dep in `<dependencyManagement>`. |
| `docs/event-driven.md` (or `docs/di-and-scopes.md` — whichever exists today) | Add a small section cross-linking to the example. |
| `docs/roadmap.md` | Add a ✅ entry under "What ships today" for the example. |

---

## Task 1: Module skeleton + reactor wiring

**Files:**
- Create: `tiko-examples/09_http_javalin/pom.xml`
- Modify: `tiko-examples/pom.xml`
- Modify: `pom.xml` (root)

- [ ] **Step 1: Create the feature branch**

```bash
git checkout main && git pull --ff-only
git checkout -b feat/http-javalin-example
```

- [ ] **Step 2: Add Javalin to root pom's `<properties>` and `<dependencyManagement>`**

Edit `pom.xml` (root). In `<properties>` add:

```xml
<javalin.version>6.4.0</javalin.version>
```

(Confirm `6.4.0` is the current Javalin 6.x release at the time of execution — bump as needed.)

In `<dependencyManagement><dependencies>`, add:

```xml
<dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin</artifactId>
    <version>${javalin.version}</version>
</dependency>
```

- [ ] **Step 3: Register the new module in `tiko-examples/pom.xml`**

Add `<module>09_http_javalin</module>` to the `<modules>` block, in numeric order (after `08_kafka_order_warehouse`).

- [ ] **Step 4: Create `tiko-examples/09_http_javalin/pom.xml`**

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

    <artifactId>09_http_javalin</artifactId>
    <packaging>jar</packaging>
    <name>09 - HTTP + Javalin Integration Example</name>
    <description>Tiko alongside an embedded Javalin server. Demonstrates the sync handler / event-bus side-effect dichotomy.</description>

    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-processor</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-runtime</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>io.javalin</groupId>
            <artifactId>javalin</artifactId>
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

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${maven-shade.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.tiko.examples.http.Main</mainClass>
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

- [ ] **Step 5: Create empty source directories**

```bash
mkdir -p tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http
mkdir -p tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http
```

- [ ] **Step 6: Verify the reactor still builds**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" install -DskipTests`
Expected: `BUILD SUCCESS`. The new module compiles (no sources yet) and installs an empty jar. No annotation-processing output because there are no `@Component` classes yet.

- [ ] **Step 7: Commit**

```bash
git add pom.xml tiko-examples/pom.xml tiko-examples/09_http_javalin/
git commit -m "feat(examples): scaffold 09_http_javalin module"
```

---

## Task 2: Domain records

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Ticket.java`
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/CreateTicketRequest.java`

- [ ] **Step 1: Create `Ticket.java`**

```java
package io.tiko.examples.http;

import java.time.Instant;
import java.util.UUID;

/** Domain record: the canonical post-create representation of a ticket. */
public record Ticket(UUID id, String title, Instant createdAt) {}
```

- [ ] **Step 2: Create `CreateTicketRequest.java`**

```java
package io.tiko.examples.http;

/** DTO record parsed from the POST /tickets JSON body. */
public record CreateTicketRequest(String title) {}
```

- [ ] **Step 3: Verify it compiles**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Ticket.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/CreateTicketRequest.java
git commit -m "feat(examples): add Ticket and CreateTicketRequest records"
```

---

## Task 3: `TicketService` — TDD

**Files:**
- Create: `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketServiceTest.java`
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketService.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketServiceTest.java`:

```java
package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TicketServiceTest {

    @Test
    void createReturnsTicketWithGeneratedIdAndTitle() {
        var svc = new TicketService();
        var ticket = svc.create(new CreateTicketRequest("first"));

        assertThat(ticket.id()).isNotNull();
        assertThat(ticket.title()).isEqualTo("first");
        assertThat(ticket.createdAt()).isNotNull();
    }

    @Test
    void findReturnsTheCreatedTicket() {
        var svc = new TicketService();
        var created = svc.create(new CreateTicketRequest("second"));

        var found = svc.find(created.id());

        assertThat(found).contains(created);
    }

    @Test
    void findReturnsEmptyForUnknownId() {
        var svc = new TicketService();
        assertThat(svc.find(java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    void createRejectsBlankTitle() {
        var svc = new TicketService();
        assertThatThrownBy(() -> svc.create(new CreateTicketRequest("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title must not be blank");
    }
}
```

- [ ] **Step 2: Verify it fails to compile (TicketService not yet defined)**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin test-compile`
Expected: `BUILD FAILURE`, error message mentions `TicketService` cannot be resolved.

- [ ] **Step 3: Implement `TicketService.java`**

Create `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketService.java`:

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ticket store. HTTP-free by design — a future native HTTP transport
 * could route requests straight at this bean without any rewiring.
 */
@Component(scope = Scope.SINGLETON)
public class TicketService {

    private final Map<UUID, Ticket> store = new ConcurrentHashMap<>();

    public Ticket create(CreateTicketRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        var ticket = new Ticket(UUID.randomUUID(), req.title(), Instant.now());
        store.put(ticket.id(), ticket);
        return ticket;
    }

    public Optional<Ticket> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin test`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketServiceTest.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketService.java
git commit -m "feat(examples): TicketService in-memory store + unit tests"
```

---

## Task 4: Event payload + RequestId types

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketCreated.java`
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestId.java`
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestIdImpl.java`

- [ ] **Step 1: Create `TicketCreated.java`**

```java
package io.tiko.examples.http;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published after a successful ticket creation. Carries the
 * server-assigned identity plus the request-scoped correlation ID so async
 * subscribers can read it off-thread (the request scope has torn down by
 * then).
 */
public record TicketCreated(UUID id, String title, String requestId, Instant createdAt) {}
```

- [ ] **Step 2: Create `RequestId.java`**

```java
package io.tiko.examples.http;

/**
 * Per-request correlation ID. The interface exists so a SINGLETON consumer
 * (e.g., TicketHttpRoutes) can have a REQUEST-scoped implementation injected
 * — Tiko generates a proxy at compile time that delegates to the current
 * scope's instance.
 */
public interface RequestId {
    String value();
}
```

- [ ] **Step 3: Create `RequestIdImpl.java`**

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * REQUEST-scoped {@link RequestId}: each scope entry constructs a fresh
 * instance with its own UUID. Re-reading {@code value()} during the request
 * returns the same string.
 */
@Component(scope = Scope.REQUEST)
public class RequestIdImpl implements RequestId {

    private final String value = UUID.randomUUID().toString();

    @Override
    public String value() {
        return value;
    }
}
```

- [ ] **Step 4: Verify compile + annotation processing**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`. Tiko's processor generates `RequestIdImplFactory` in `target/generated-sources/annotations/`.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketCreated.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestId.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestIdImpl.java
git commit -m "feat(examples): TicketCreated event + RequestId interface + RequestIdImpl (REQUEST scope)"
```

---

## Task 5: Synchronous event subscribers

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/AuditLogger.java`
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/MetricsCounter.java`

- [ ] **Step 1: Create `AuditLogger.java`**

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.logging.Logger;

/** Synchronous audit handler. Runs inline before {@code EventBus.publish} returns. */
@Component(scope = Scope.SINGLETON)
public class AuditLogger {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.audit");

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        LOG.info(() -> "[AUDIT req=" + event.requestId() + "] ticket " + event.id()
                + " '" + event.title() + "' created at " + event.createdAt());
    }
}
```

- [ ] **Step 2: Create `MetricsCounter.java`**

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous metrics handler. Exposes {@link #count()} for tests. */
@Component(scope = Scope.SINGLETON)
public class MetricsCounter {

    private final AtomicLong ticketsCreated = new AtomicLong();

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        ticketsCreated.incrementAndGet();
    }

    public long count() {
        return ticketsCreated.get();
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/AuditLogger.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/MetricsCounter.java
git commit -m "feat(examples): AuditLogger + MetricsCounter sync subscribers"
```

---

## Task 6: Asynchronous event subscriber

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/NotificationSender.java`

- [ ] **Step 1: Create `NotificationSender.java`**

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Async notification handler — runs on Tiko's framework executor, NOT on the
 * HTTP worker thread. The HTTP response is already on the wire by the time
 * this runs. Exposes a {@link CountDownLatch} so the integration test can
 * deterministically wait for it instead of sleeping.
 */
@Component(scope = Scope.SINGLETON)
public class NotificationSender {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.notify");

    /**
     * Test hook. The integration test calls {@link #expectNotifications(int)}
     * before issuing requests, then awaits this latch. Initialised to a
     * zero-count latch so production code that never touches it doesn't
     * block.
     */
    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(0));

    /** Resets the latch to count down the given number of notifications. */
    public CountDownLatch expectNotifications(int count) {
        var fresh = new CountDownLatch(count);
        latch.set(fresh);
        return fresh;
    }

    @EventHandler(async = true)
    public void onTicketCreated(TicketCreated event) {
        LOG.info(() -> "[NOTIFY req=" + event.requestId() + "] would email about ticket " + event.id());
        latch.get().countDown();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/NotificationSender.java
git commit -m "feat(examples): NotificationSender async subscriber"
```

---

## Task 7: `RequestTimer` lifecycle subscriber

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestTimer.java`

- [ ] **Step 1: Create `RequestTimer.java`**

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.RequestEndingEvent;
import io.tiko.events.RequestStartedEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Subscribes to Tiko's framework lifecycle events to demonstrate that every
 * HTTP request — including reads, including 404s — opens and closes a Tiko
 * request scope, and therefore gets per-request observability for free.
 *
 * <p>The framework's {@code RequestStartedEvent.requestId()} is a separate
 * identifier from the application's {@link RequestId#value()}. Both are
 * carried in the logs so tests can correlate; in a real app the framework
 * ID is what oncall would search for in distributed traces, and the
 * application ID would come from / propagate to a header like
 * {@code X-Request-Id}.
 */
@Component(scope = Scope.SINGLETON)
public class RequestTimer {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.timer");

    private final AtomicInteger startedCount = new AtomicInteger();
    private final AtomicInteger endedCount = new AtomicInteger();

    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        startedCount.incrementAndGet();
        LOG.info(() -> "[REQ " + event.requestId() + "] started at " + event.timestamp());
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        endedCount.incrementAndGet();
        LOG.info(() -> "[REQ " + event.requestId() + "] completed in " + event.duration());
    }

    public int startedCount() {
        return startedCount.get();
    }

    public int endedCount() {
        return endedCount.get();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestTimer.java
git commit -m "feat(examples): RequestTimer subscribes to lifecycle events"
```

---

## Task 8: `TikoJavalin` middleware decorator

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TikoJavalin.java`

- [ ] **Step 1: Create `TikoJavalin.java`**

```java
package io.tiko.examples.http;

import io.javalin.http.Handler;
import io.tiko.Container;

/**
 * Tiny middleware bridge: wraps a Javalin {@link Handler} so each invocation
 * runs inside a Tiko request scope. Drop this in front of every route
 * registration to get request-scope semantics for the whole request lifecycle
 * — body parsing, business logic, event publishing, response serialization.
 *
 * <p>Why a helper instead of opening the scope inside each handler: ergonomics.
 * Every route would otherwise need an identical {@code runInRequestScope}
 * wrapper, which is exactly the kind of boilerplate middleware exists to
 * eliminate.
 *
 * <p>This class is part of the example module, not framework code. If
 * something this thin proves valuable enough to live in a shared library,
 * it gets promoted to a {@code tiko-http-bridge} module in a follow-up — but
 * not before real users hit ergonomic friction.
 */
public final class TikoJavalin {

    private TikoJavalin() {}

    /**
     * Returns a new {@link Handler} that opens a Tiko request scope around
     * the delegate's {@link Handler#handle(io.javalin.http.Context)}.
     * Javalin's checked-exception declaration is wrapped in a
     * {@link RuntimeException}; Javalin's own exception mapper unwraps it on
     * its side and applies the user's configured exception handlers.
     */
    public static Handler scoped(Container container, Handler delegate) {
        return ctx -> container.runInRequestScope(() -> {
            try {
                delegate.handle(ctx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TikoJavalin.java
git commit -m "feat(examples): TikoJavalin.scoped Handler decorator"
```

---

## Task 9: `TicketHttpRoutes` bridge

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketHttpRoutes.java`

**Note on design:** Tiko does not expose `EventBus` (or `Container`) as an injectable bean — both are framework infrastructure, reachable only via `container.getEventBus()` after bootstrap. So this bridge is *not* a `@Component`: `Main` constructs it once after the container is built and passes it to route registration. `RequestId` resolves per-request via `container.get(RequestId.class)` from inside the open scope. The example still teaches the REQUEST-scoped bean concept; it just resolves the scoped bean via the container rather than via auto-proxy injection.

- [ ] **Step 1: Create `TicketHttpRoutes.java`**

```java
package io.tiko.examples.http;

import io.javalin.http.Context;
import io.tiko.Container;
import io.tiko.EventBus;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge layer between Javalin's HTTP machinery and Tiko's beans. The only
 * file in this example that imports both {@code io.javalin} and {@code io.tiko}.
 *
 * <p>Bridge methods are plain straight-line code — they do not call
 * {@code runInRequestScope} themselves; the {@code TikoJavalin.scoped(...)}
 * decorator wraps the entire delegate invocation at registration time.
 *
 * <p>Not a {@code @Component}: it depends on {@link EventBus}, which Tiko
 * exposes off the {@link Container} rather than via DI. {@link Main} builds
 * one instance after container bootstrap. {@link RequestId} is resolved
 * per-request via {@code container.get(RequestId.class)} from inside the
 * open scope.
 */
public final class TicketHttpRoutes {

    private final TicketService tickets;
    private final EventBus eventBus;
    private final Container container;

    public TicketHttpRoutes(TicketService tickets, EventBus eventBus, Container container) {
        this.tickets = tickets;
        this.eventBus = eventBus;
        this.container = container;
    }

    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateTicketRequest.class);
        try {
            var ticket = tickets.create(req);
            var reqId = container.get(RequestId.class).value();
            eventBus.publish(new TicketCreated(ticket.id(), ticket.title(), reqId, Instant.now()));
            ctx.status(201).json(ticket);
        } catch (IllegalArgumentException badInput) {
            ctx.status(400).json(java.util.Map.of("error", badInput.getMessage()));
        }
    }

    public void handleGet(Context ctx) {
        var id = UUID.fromString(ctx.pathParam("id"));
        tickets.find(id).ifPresentOrElse(t -> ctx.status(200).json(t), () -> ctx.status(404));
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`. Component count unchanged (this file is not a `@Component`).

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketHttpRoutes.java
git commit -m "feat(examples): TicketHttpRoutes bridge — plain straight-line handlers"
```

---

## Task 10: `Main` bootstrap

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Main.java`

- [ ] **Step 1: Create `Main.java`**

```java
package io.tiko.examples.http;

import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.runtime.Tiko;

/**
 * Bootstrap. Builds the Tiko container, fetches the bridge bean, builds a
 * Javalin app, registers routes through the {@link TikoJavalin#scoped}
 * decorator so each request runs inside a Tiko request scope, and wires a
 * shutdown hook in shutdown-safe order.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Container container = Tiko.create();
        TicketHttpRoutes routes = new TicketHttpRoutes(
                container.get(TicketService.class), container.getEventBus(), container);

        Javalin app = Javalin.create();
        app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
        app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));

        int port = portFromEnv();
        app.start(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Stop Javalin first: drains in-flight requests before Tiko teardown
            // so @PreDestroy / AutoCloseable.close() never run on a bean still
            // being read by an HTTP worker.
            app.stop();
            container.shutdown();
        }, "tiko-http-shutdown"));
    }

    private static int portFromEnv() {
        String value = System.getenv("TIKO_HTTP_PORT");
        if (value == null || value.isBlank()) return 8080;
        return Integer.parseInt(value.trim());
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin compile`
Expected: `BUILD SUCCESS`. The Tiko processor generates `TikoContainerImpl_<hash>` covering the module's `@Component` classes (TicketHttpRoutes is constructed manually, so it is not a component).

- [ ] **Step 3: Verify the shaded jar builds**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin package -DskipTests`
Expected: `BUILD SUCCESS`, a `target/09_http_javalin-0.1.0.jar` file exists.

- [ ] **Step 4: Smoke test the jar manually (optional but recommended)**

Run: `java -jar tiko-examples/09_http_javalin/target/09_http_javalin-0.1.0.jar &`
Then in another shell:
```bash
curl -X POST http://localhost:8080/tickets -H 'Content-Type: application/json' -d '{"title":"first"}'
```
Expected output: HTTP 201 with a JSON body like `{"id":"...","title":"first","createdAt":"..."}`. The server-side logs should show `[REQ ...] started`, `[AUDIT req=...]`, and `[REQ ...] completed in PT...`.

Stop the server (`fg` then Ctrl+C, or `kill %1`).

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Main.java
git commit -m "feat(examples): Main wires Tiko + Javalin with shutdown ordering"
```

---

## Task 11: Integration test scaffold + POST happy path

**Files:**
- Create: `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java`

- [ ] **Step 1: Create the scaffold + first test**

```java
package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test: real Tiko container + real Javalin server on a
 * random port + real HTTP via {@link HttpClient}. Each test sets up and tears
 * down per-test for isolation.
 */
class TicketHttpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Container container;
    private Javalin app;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        container = Tiko.create();
        var routes = new TicketHttpRoutes(
                container.get(TicketService.class), container.getEventBus(), container);
        app = Javalin.create();
        app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
        app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));
        app.start(0); // 0 = OS picks a free port
        port = app.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (container != null) container.shutdown();
    }

    @Test
    void postCreatesTicketAndReturns201() throws Exception {
        var notifications = container.get(NotificationSender.class).expectNotifications(1);
        var metrics = container.get(MetricsCounter.class);
        long countBefore = metrics.count();

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"first ticket\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(body.get("title").asText()).isEqualTo("first ticket");

        assertThat(metrics.count()).isEqualTo(countBefore + 1);

        boolean fired = notifications.await(5, TimeUnit.SECONDS);
        assertThat(fired).as("async NotificationSender ran").isTrue();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin test`
Expected: 5 tests pass (4 from `TicketServiceTest` + 1 from `TicketHttpIntegrationTest`).

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java
git commit -m "test(examples): integration test scaffold + POST happy path"
```

---

## Task 12: Integration test — GET happy path + per-request `requestId` distinctness

**Files:**
- Modify: `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java`

- [ ] **Step 1: Add a recording subscriber for the test, then a second test method**

We need to observe the `TicketCreated` event payloads to assert per-request `requestId` distinctness. The cleanest path is a dedicated `@Component` that captures events into a list — added to `src/main/java` so the annotation processor picks it up. Add this class:

Create `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketCreatedRecorder.java`:

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only-but-always-present recorder of {@link TicketCreated} events. Lives
 * in main sources so the annotation processor wires it as a normal sync
 * subscriber. Cost in production: a single CopyOnWriteArrayList growing on
 * each POST. Negligible; the example is not a benchmark and this keeps the
 * test surface honest (it observes the same event everyone else sees).
 */
@Component(scope = Scope.SINGLETON)
public class TicketCreatedRecorder {

    private final List<TicketCreated> events = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        events.add(event);
    }

    public List<TicketCreated> events() {
        return List.copyOf(events);
    }
}
```

Then in `TicketHttpIntegrationTest.java`, add this test method below `postCreatesTicketAndReturns201`:

```java
    @Test
    void getReturnsTicketAfterPostAndPerRequestIdsAreDistinct() throws Exception {
        var recorder = container.get(TicketCreatedRecorder.class);
        int eventsBefore = recorder.events().size();

        // POST twice
        HttpResponse<String> first = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"a\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"b\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);

        var emitted = recorder.events();
        assertThat(emitted).hasSize(eventsBefore + 2);

        var lastTwo = emitted.subList(emitted.size() - 2, emitted.size());
        assertThat(lastTwo.get(0).requestId())
                .as("each request gets its own REQUEST-scoped requestId")
                .isNotEqualTo(lastTwo.get(1).requestId());

        // GET back the first one.
        String firstId = JSON.readTree(first.body()).get("id").asText();
        HttpResponse<String> got = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/" + firstId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(got.body()).get("title").asText()).isEqualTo("a");
    }
```

- [ ] **Step 2: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin test`
Expected: 6 tests pass.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/TicketCreatedRecorder.java tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java
git commit -m "test(examples): GET happy path + per-request requestId distinctness"
```

---

## Task 13: Integration test — 404 + no domain events on the read path

**Files:**
- Modify: `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java`

- [ ] **Step 1: Add the test method**

In `TicketHttpIntegrationTest.java`, after the previous test, add:

```java
    @Test
    void getReturns404ForUnknownIdAndDoesNotFireDomainEvent() throws Exception {
        var metrics = container.get(MetricsCounter.class);
        var recorder = container.get(TicketCreatedRecorder.class);
        long metricsBefore = metrics.count();
        int eventsBefore = recorder.events().size();

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(
                                "http://localhost:" + port + "/tickets/" + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(metrics.count()).as("read path must not fire TicketCreated").isEqualTo(metricsBefore);
        assertThat(recorder.events()).hasSize(eventsBefore);
    }
```

- [ ] **Step 2: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin test`
Expected: 7 tests pass.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java
git commit -m "test(examples): GET 404 fires no domain event"
```

---

## Task 14: Integration test — lifecycle events fire per request

**Files:**
- Modify: `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java`

- [ ] **Step 1: Add the test method**

In `TicketHttpIntegrationTest.java`, after the previous test, add:

```java
    @Test
    void lifecycleEventsFireForEveryHttpRequest() throws Exception {
        var timer = container.get(RequestTimer.class);
        int startedBefore = timer.startedCount();
        int endedBefore = timer.endedCount();

        // Three requests: 1 POST, 1 GET success, 1 GET 404. The framework
        // publishes RequestStarted + RequestEnding for each — three of each.
        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"lifecycle\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(201);

        String createdId = JSON.readTree(post.body()).get("id").asText();
        HttpResponse<String> getOk = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/" + createdId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(getOk.statusCode()).isEqualTo(200);

        HttpResponse<String> get404 = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/tickets/"
                                + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get404.statusCode()).isEqualTo(404);

        assertThat(timer.startedCount() - startedBefore).isEqualTo(3);
        assertThat(timer.endedCount() - endedBefore).isEqualTo(3);
    }
```

- [ ] **Step 2: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/09_http_javalin test`
Expected: 8 tests pass.

- [ ] **Step 3: Run the full reactor to confirm no regression**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" install`
Expected: `BUILD SUCCESS`. All modules in the reactor still build; all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/TicketHttpIntegrationTest.java
git commit -m "test(examples): lifecycle events fire for every HTTP request"
```

---

## Task 15: Module `README.md`

**Files:**
- Create: `tiko-examples/09_http_javalin/README.md`

- [ ] **Step 1: Create `README.md`**

```markdown
# 09 — HTTP + Javalin Integration

How to put a Tiko-managed application behind an existing HTTP server, with a
clean separation between the **sync request → response path** (what the
client is waiting on) and **event-bus side effects** (audit, metrics,
notifications — the work that happens *because of* the request but the
response doesn't depend on).

## The dichotomy

Every HTTP request flows through two parallel tracks:

| Track | What runs | Wait? |
|---|---|---|
| **Sync request → response** | `TicketService.create` / `find`, response serialization | Yes — the HTTP client is on the wire |
| **Event-bus side effects** | `AuditLogger`, `MetricsCounter` (sync); `NotificationSender` (`async = true`) | No — the response is sent before async side effects finish |

The bridge layer (`TicketHttpRoutes`) does the minimum work the client is
waiting on, then publishes one `TicketCreated` event. Side effects subscribe
via `@EventHandler`. The HTTP response goes out as soon as the sync side
finishes — async side effects keep running on Tiko's framework executor.

## Request-scope wrapping (the middleware)

Every route is registered via `TikoJavalin.scoped(container, handler)`, a
six-line `Handler` decorator that opens a Tiko request scope around the entire
delegate body — including body parsing, the handler, the event publish, and
**response serialization** (so a custom Jackson serializer needing DI can
still reach Tiko-managed beans).

This gives the example three things for free:

- `RequestStartedEvent` / `RequestEndingEvent` fire automatically every HTTP
  request. `RequestTimer` subscribes to demonstrate per-request observability
  with zero per-route boilerplate.
- REQUEST-scoped beans (here: `RequestIdImpl`) are reachable from inside the
  handler chain. The bridge injects `RequestId` (proxied) and stamps each
  request's UUID onto the published `TicketCreated` event.
- Sync `@EventHandler` subscribers run inside the same scope and can read
  REQUEST-scoped beans directly. Async subscribers run on a different thread,
  don't see the scope, and read whatever they need from the event payload.

## Build and run

```bash
mvn -pl tiko-examples/09_http_javalin -am package
java -jar tiko-examples/09_http_javalin/target/09_http_javalin-0.1.0.jar &

# In another shell:
curl -X POST http://localhost:8080/tickets \
     -H 'Content-Type: application/json' \
     -d '{"title":"my first ticket"}'

curl http://localhost:8080/tickets/<id-from-the-previous-response>
```

Default port is `8080`; override with `TIKO_HTTP_PORT=9090 java -jar ...`.

## Files at a glance

- `TicketService` — business logic. Pure Tiko bean, **no HTTP imports**.
- `TicketHttpRoutes` — bridge. The only file in the module that imports both
  `io.tiko` and `io.javalin`. Plain straight-line handlers; the
  `runInRequestScope` lives in the decorator, not here.
- `TikoJavalin` — `Handler scoped(Container, Handler)`. The middleware.
- `RequestIdImpl` — REQUEST-scoped `@Component` implementing `RequestId`.
  Cross-scope injection into the SINGLETON bridge works via a compile-time
  proxy.
- `RequestTimer` — `@EventHandler` on `RequestStartedEvent` /
  `RequestEndingEvent`. Demonstrates the framework's lifecycle events.
- `AuditLogger`, `MetricsCounter`, `NotificationSender` — the three
  side-effect handlers (two sync, one async).
- `Main` — bootstrap.

## What this example deliberately does NOT show

- **A native HTTP transport.** That's the follow-up issue tracked separately
  ("Issue 2" — native HTTP with compile-time request-response pipeline
  detection). The point of this example is that Tiko integrates cleanly with
  whatever HTTP server you already have.
- **Authentication, middleware chains, TLS.** Layer those in via Javalin's own
  facilities; they're orthogonal to the Tiko integration.
- **Multiple HTTP servers.** Javalin is one choice; the same pattern ports to
  Helidon Nima, Jetty embedded, the JDK's `HttpServer`, etc. Swap the import
  and the registration syntax; everything else stays.
```

- [ ] **Step 2: Commit**

```bash
git add tiko-examples/09_http_javalin/README.md
git commit -m "docs(examples): README for the http+javalin integration example"
```

---

## Task 16: Cross-reference in main docs + roadmap

**Files:**
- Modify: `docs/events.md`
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Append a "Using Tiko behind an existing HTTP server" section to `docs/events.md`**

Append this block to the END of `docs/events.md` (preserve everything that's already there):

```markdown
## Using Tiko behind an existing HTTP server

Tiko has no opinion about which HTTP server you use. The recommended pattern
keeps your sync request → response path independent of the event bus, while
publishing one event per business action that subscribers can react to
without the HTTP client waiting on them.

See `tiko-examples/09_http_javalin/` for a runnable example with Javalin: a
six-line `Handler` decorator opens a Tiko request scope around each route,
the bridge bean stays plain straight-line code, and three subscribers
(audit, metrics, async notification) demonstrate the sync-vs-async-side-effect
axis. The pattern ports to Helidon, Jetty, the JDK's `HttpServer`, etc. — swap
the imports and registration syntax; everything else stays.
```

- [ ] **Step 2: Add a ✅ entry to the roadmap's "What ships today"**

In `docs/roadmap.md`, in the `## What ships today` block, after the existing entries, add:

```markdown
- ✅ HTTP + Javalin integration example — `tiko-examples/09_http_javalin/`: a runnable demo showing how Tiko lives behind any HTTP server (`TikoJavalin.scoped` middleware opens a request scope around each route, sync request→response path is independent of the event bus, three subscribers demonstrate sync/async side effects).
```

- [ ] **Step 3: Verify Spotless gate still clean**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" spotless:check`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: cross-link the http+javalin integration example from main docs and roadmap"
```

---

## Task 17: Final reactor build + push

**Files:**
- (no changes, validation only)

- [ ] **Step 1: Run the full reactor build**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" install`
Expected: `BUILD SUCCESS`. Reactor summary includes `09 - HTTP + Javalin Integration Example`.

- [ ] **Step 2: Check `git status` is clean**

Run: `git status`
Expected: nothing to commit; working tree clean.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin feat/http-javalin-example
```

- [ ] **Step 4: Open a PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "feat(examples): http + javalin integration example" \
    --body "$(cat <<'EOF'
## Summary

Ships `tiko-examples/09_http_javalin/` — Issue 1 of the HTTP support story
(spec at `docs/superpowers/specs/2026-05-14-http-javalin-integration-example-design.md`).
Demonstrates the sync request → response path vs event-bus side effects
dichotomy with a runnable Javalin-fronted Tiko app.

### Key pieces

- **`TikoJavalin.scoped(Container, Handler)`** — six-line `Handler` decorator
  that opens a Tiko request scope around every route's full delegate body
  (body parsing → handler → event publish → response serialization).
- **Sync side effects** (`AuditLogger`, `MetricsCounter`) — `@EventHandler` on
  `TicketCreated`, run inline before `EventBus.publish` returns.
- **Async side effect** (`NotificationSender`) — `@EventHandler(async = true)`,
  runs on Tiko's framework executor; the HTTP response is already on the wire.
- **`RequestTimer`** — `@EventHandler` on Tiko's `RequestStartedEvent` /
  `RequestEndingEvent`, demonstrates per-request lifecycle observability for
  free.
- **`RequestIdImpl`** — REQUEST-scoped `@Component`, proxied into the
  SINGLETON bridge so each HTTP request gets its own correlation ID.

### Test plan

- [x] Unit test (`TicketServiceTest`) for the business logic.
- [x] Integration test (`TicketHttpIntegrationTest`) covering POST happy
      path, GET happy path, per-request `requestId` distinctness, GET 404
      with no event firing, lifecycle events fire per HTTP request.
- [x] Manual `curl` smoke test against the shaded jar.
- [x] Full reactor `mvn install` green.
- [x] Spotless gate clean.
EOF
)"
```

- [ ] **Step 5: Watch CI**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: all checks pass. If any fail, diagnose the specific failure (most likely Spotless formatting — fix with `mvn -pl '!tiko-bom' spotless:apply` and push again).

- [ ] **Step 6: Hand off for manual merge**

Per project policy (branch protection), the user merges in the GitHub UI. After they confirm merge:

```bash
git checkout main
git pull --ff-only
git branch -d feat/http-javalin-example
git fetch --prune origin
```

---

## Done

The example builds, runs, has passing tests, and is documented. Real users can
copy the pattern; the spec's acceptance criteria are met.
