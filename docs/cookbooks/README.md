# Tiko cookbooks

Recommended integrations for areas Tiko deliberately doesn't ship. Each
cookbook is a docs page paired with a runnable numbered example under
`tiko-examples/`. The cookbook documents the *why* and the wiring; the
example proves it compiles, runs, and stays green under CI.

## Available

- [Persistence (raw JDBC + HikariCP)](persistence.md) — `tiko-examples/10_persistence_jdbc/`. REQUEST-scoped JDBC transactions wrapping both an HTTP entry point and a batch flow with shared repositories. Demonstrates the auto-proxy mechanism on a JDK interface (`java.sql.Connection`) and the concrete REQUEST-vs-EVENT scope distinction.

## Planned

- **Security** — auth/authz at the HTTP boundary. Likely leverages whatever HTTP server you've picked (Javalin in `09_http_javalin`).
- **Resilience** — Resilience4j integration (retry, circuit breaker, bulkhead) around `@Component` boundaries.
- **Kafka surfacing** — cross-references to `08_kafka_order_warehouse` and a "when to reach for distributed events" narrative.
- **Non-goals + recommended integrations** — single top-level page naming the boundary of what Tiko owns and the recommended pairing for each non-goal.

The cookbook track exists because reviewers consistently read silence on
persistence/security/resilience as "framework is incomplete" rather than
"framework is deliberately small". Cookbooks close that documentation gap
without expanding Tiko's surface.
