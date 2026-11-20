# Tiko cookbooks

Recommended integrations for areas Tiko deliberately doesn't ship. Each
cookbook is a docs page paired with a runnable numbered example under
`tiko-examples/`. The cookbook documents the *why* and the wiring; the
example proves it compiles, runs, and stays green under CI.

## Available

- [Persistence (raw JDBC + HikariCP)](persistence.md) — `tiko-examples/10_persistence_jdbc/`. One JDBC transaction per unit of work (EVENT scope), wrapping both an HTTP entry point and a batch flow with shared repositories. Demonstrates the auto-proxy mechanism on a JDK interface (`java.sql.Connection`).
- [HTTP service (a server the container owns)](http.md) — `tiko-examples/15_quickstart/`, with `09_http_javalin/` and `17_transactional_outbox/`. Javalin routes with one unit of work per request through the injected `UnitOfWork`, a server `@Component` the container starts at boot and stops before it drains async work, a resource that runs from boot, `main` as `Tiko.daemon(...).awaitShutdown()`, what a taken port and a request in flight at shutdown do, and testing on a free port.
- [Kafka transport](kafka.md) — `tiko-examples/08_kafka_order_warehouse/`. The `tiko-kafka` source/sink bridges: a topic in via a non-void source + sibling event trigger, an event out via a sink keyed by a payload accessor, broker config under `tiko.kafka.*`, and the poison-record story.

- [Ktor (one unit of work per request)](ktor.md) — `tiko-examples-gradle/kotlin-ktor-exposed/`. Kotlin routes that run their work in one unit of work across suspension with `inEventScope` from `tiko-coroutines`: blocking JDBC on `Dispatchers.IO` inside the unit, what a client disconnect does, and parallel work in a request (`async` children are units of their own).

## Planned

- **Security** — auth/authz at the HTTP boundary. Likely leverages whatever HTTP server you've picked (Javalin in `09_http_javalin`).
- **Non-goals + recommended integrations** — single top-level page naming the boundary of what Tiko owns and the recommended pairing for each non-goal.

Resilience was previously slated as a cookbook (Resilience4j around `@Component` boundaries) but has since been promoted to a first-party milestone — see Phase 7 in [docs/roadmap.md](../roadmap.md). The framework owns the async event bus, so handler timeouts / retries / backpressure / DLQ ship in Tiko rather than as a pairing recipe.

The cookbook track exists because reviewers consistently read silence on
persistence/security/resilience as "framework is incomplete" rather than
"framework is deliberately small". Cookbooks close that documentation gap
without expanding Tiko's surface.
