# Scope Model Unification — Design (#226)

**Status:** Decided 2026-05-29. Decision = option 1 ("adopt now, publish on the aligned shape"),
with per-frame lifecycle firing. Gates the `0.x.0` Maven Central publish.

## 1. The decision

Collapse the public scope model to **three core scopes** and publish on that shape so it never
becomes a breaking change:

```
SINGLETON   application lifetime
EVENT       one unit of work; nestable; the generic primitive
PROTOTYPE   per injection (default)
```

`Scope.REQUEST` **leaves the core enum.** REQUEST was only ever the *HTTP flavour* of a unit of
work — the outermost unit whose stimulus is an HTTP request. The general **typed-flavour API**
(`@ScopeFlavour` / `@RequestScoped`, identifying a unit's *origin*) is **deferred** until a real
HTTP/batch integration drives it. In `0.x.0`, beans bind to the **current** unit only.

**A unit-scoped resource belongs to exactly one unit of work.** A live `Transaction` / `Connection`
is instance-bound and non-serializable — it cannot follow work that detaches or distributes, which
the model permits at every async/transport hop. So sharing such a resource *across* units (the old
"one transaction spanning a batch of REQUEST-scoped events" pattern) is an **explicit non-goal**, not
a deferred feature. "One transaction across a batch" is really *one* unit — open the transaction,
loop the items inside it, commit. Genuinely independent (distributable) events each own their
resource; cross-unit consistency is a **saga / outbox / idempotency** concern, above the DI layer.

### Why now (publish gate)

`Scope.REQUEST` / `runInRequestScope` are public API; changing them *after* the Phase 5 publish
would be a breaking change to the most-used scope. The published scope *shape* must therefore be
fixed before publication. The acceptance below requires the API to actually reflect the new shape —
this is not just a recorded decision, the migration lands before publish.

### Why it holds (the model was already there)

A **unit of work = the synchronous reach of an inbound stimulus** (HTTP request, consumed message,
scheduled job, async dispatch), bounded at every async / transport hop. The current cross-scope
proxy rules are already purely lifetime-based — `EVENT → REQUEST` is direct (inner unit → outer),
`REQUEST → EVENT` needs a proxy (outer → inner), `SINGLETON → either` needs a proxy. That is exactly
**one nestable scope at two depths wearing two names.** Collapsing REQUEST into a nestable EVENT
removes a code path rather than adding one.

## 2. Published API — the fixed surface

### 2.1 `Scope` enum (`tiko-api`)

`SINGLETON, EVENT, PROTOTYPE`. Default for `@Component` stays `PROTOTYPE`.

### 2.2 `Container`

- **Remove** `runInRequestScope(Runnable)` and `supplyInRequestScope(Supplier<T>)`.
- `runInEventScope(Runnable)` / `supplyInEventScope(Supplier<T>)` become **nestable**: an inner call
  opens a *child* unit. Today's "one REQUEST containing many EVENTs" batch pattern becomes an outer
  `runInEventScope { … inner runInEventScope … }`.
- No new public API is added in `0.x.0` (the flavour entry point is deferred).

### 2.3 Lifecycle events (`io.tiko.events`)

- **Remove** `RequestStartedEvent` and `RequestEndingEvent`.
- **Keep** `EventStartedEvent` / `EventEndingEvent`. They fire **per unit frame, including nested
  frames** — each unit is a real scope with its own beans and teardown, so each open/close is
  observable. (Decided 2026-05-29.)
- The request-vs-event *distinction* that two event types provided is intentionally dropped for
  `0.x.0`; the typed-flavour API reintroduces a typed distinction later, when an integration needs it.

### 2.4 Cross-scope proxy matrix

Collapses from 4×4 to 3×3. A proxy (requiring an interface) is generated only when a longer-lived
bean holds a shorter-lived one:

| Consumer ↓ / Dependency → | SINGLETON | EVENT          | PROTOTYPE |
| ------------------------- | --------- | -------------- | --------- |
| SINGLETON                 | direct    | **proxy**      | direct    |
| EVENT                     | direct    | direct (same unit) | direct |
| PROTOTYPE                 | direct    | direct         | direct    |

The entire REQUEST row and column disappear. `EVENT → EVENT` is same-unit direct injection — there
is no "inject an ancestor unit's bean." Resources bind to the current unit only; sharing one across
units is a non-goal (see §1 and §4), not merely deferred.

## 3. Internals (`tiko-runtime`, `tiko-processor`)

- `AggregatingContainer`'s two ThreadLocals (`requestScoped` + `eventScoped`) collapse into a single
  nestable **unit-of-work stack** (`ThreadLocal<Deque<UnitFrame>>`). Entering a unit pushes a frame;
  exiting pops and tears it down (LIFO `@PreDestroy`, as today). `SINGLETON → EVENT` proxies resolve
  to the **innermost** open frame.
- `ScopeValidator` / `ProxyGenerator` / `ContainerGenerator`: drop all REQUEST handling. The proxy
  decision reduces to "is the dependency EVENT-scoped and the consumer longer-lived (SINGLETON)?".
- Behavior change: **EVENT becomes nestable.** Today EVENT is effectively the leaf depth; the unit
  stack makes `runInEventScope` re-entrant.

## 4. Explicitly deferred / out of scope for 0.x.0

- The **typed-flavour API** (`@ScopeFlavour` meta-annotation, `@RequestScoped`) — a way to *type a
  unit's origin* ("the nearest HTTP-request unit"), reintroduced when a real HTTP/batch integration
  drives it. *(Out of scope for #226 per the issue.)* It identifies units; it does **not** share
  resources across them.
- **Sharing a unit-scoped resource across units** — a **NON-GOAL**, not a deferred feature. A
  transaction/connection is instance-bound and cannot follow detached or distributed work, so the
  framework will not smuggle one across unit boundaries. Cross-unit transactional consistency is a
  saga / outbox / idempotency concern, above the DI layer (see §1).
- **Async fork / continuation** (#220 fork-a-new-root-unit, #221 carry-one-unit-across-a-gap). These
  *derive* from this model but are Phase 7 items with their own design
  (see `project_async_event_scope_model`).
- **User-defined arbitrary scopes.** Flavours are an integration extension point, not a free-for-all.

## 5. Migration surface (~270 references / ~86 files)

| Area | Work |
| --- | --- |
| `tiko-api` | `Scope` enum; `Container` (remove Request methods); delete `RequestStartedEvent`/`RequestEndingEvent`; touch `Component`/`Produces`/`PreDestroy` javadoc referencing REQUEST. |
| `tiko-processor` | `ScopeValidator`, `ProxyGenerator`, `ContainerGenerator` (33 refs), `FactoryMethodModel`; rewrite/trim REQUEST-specific tests (`CrossScopeMatrixTest`, `ProxyForProducesOutputCrossScopeTest`, `RequiredInterfaceForProxyTest`, …). |
| `tiko-runtime` | `AggregatingContainer` unit stack; `Tiko`, `TikoOptions`; runtime tests. |
| `tiko-test` | `RequestScopeTest`, `TikoTestExtension`, `RequestScopedService` fixture, `ScopeHelpersTest`. |
| examples | `01_basic_di` (REQUEST lifecycle/teardown demos), `09_http_javalin` (RequestId), **`10_persistence_jdbc`** (rework the batch to **one unit / one transaction with an internal loop**; remove the shared-transaction-across-events variant and add a short note that genuinely independent events each own their transaction — cross-event consistency is an outbox, not a shared handle), `03_events`, `12_testing`, `13_mcp_introspection`. |
| docs | `docs/di-and-scopes.md`, the scope tables + cross-scope matrix in `CLAUDE.md`, any scope mention in README. |

## 6. Implementation staging (for writing-plans)

Staged so each step compiles and the reactor stays green:

1. **`tiko-api`** — enum + `Container` + remove `Request*` events (+ javadoc).
2. **`tiko-processor`** — validator/generators/proxy; update processor tests.
3. **`tiko-runtime`** — unit-of-work stack in `AggregatingContainer`; runtime tests.
4. **`tiko-test`** — helpers + fixtures.
5. **examples + docs** — rewrite REQUEST→EVENT; rework `10_persistence` batch; rewrite scope docs.

Each stage is its own commit/PR candidate; the migration is tracked as a dedicated implementation
issue (or a small sequence), separate from this decision.

## 7. Acceptance

- The decision (option 1, per-frame lifecycle) is recorded — **this document**.
- The `0.x.0` public scope surface is `SINGLETON / EVENT / PROTOTYPE`; **no `REQUEST` appears in any
  public API** (`Scope`, `Container`, `io.tiko.events`).
- `mvn clean install` (full reactor) is green; the cross-scope proxy tests reflect the 3×3 matrix.
- `docs/di-and-scopes.md` explains the unit-of-work model and the EVENT-as-primitive shape.

## 8. Risks

- **Nestable EVENT is a behavior change.** Existing single-level EVENT usage is unaffected; new
  re-entrancy needs targeted tests (nested units → independent frames, correct LIFO teardown).
- **Lost observability distinction** (request vs event) until flavours land — acceptable pre-publish,
  called out in docs.
- **Example change** (`10_persistence`) — the shared-transaction-across-events variant is removed as
  an anti-pattern (the resource can't follow distributable work), not deferred; the batch becomes one
  unit / one transaction, with an outbox pointer for the genuinely-independent-events case.
