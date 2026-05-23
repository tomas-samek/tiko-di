# QA Playbook

A structured, repeatable QA pass over the framework, beyond what `mvn test` covers. **Seven passes** — six over the framework + one over the MCP server — each focused on one surface, each with explicit scenarios. **Extend this document when any new annotation, scope, lifecycle event, configuration feature, error case, MCP tool, or example module is introduced** — add the new item to the relevant pass's scenario list so the next QA run covers it.

## Purpose

`mvn test` answers "do the existing tests pass?" — not "do the examples still do what their READMEs say?", "do error messages still follow our format?", or "does this shipped feature have a regression net?" Those gaps decay silently. This playbook is the safety net.

## When to run

- **Before tagging a release.** Run all seven passes; file findings; gate the release on critical bugs (see "Bug-severity heuristic" below).
- **After adding a new annotation, scope, lifecycle event, or configuration feature.** Extend the relevant pass's scenarios first, then run Passes 2, 4, 5, 6 at minimum.
- **After non-trivial processor changes.** Pass 2 + Pass 4.
- **After modifying examples or their parent README.** Pass 1 + Pass 6.
- **After modifying the MCP server, its emitted topology, or any tool's schema.** Pass 7.

Cost from the 2026-05-23 run (one operator + subagents): Pass 1 ~90 min, Pass 2 ~30 min, Pass 3 ~20 min, Pass 4 ~45 min, Pass 5 ~30 min, Pass 6 ~30 min, Pass 7 ~30 min. Total ~4–5 hours wall-clock, dominated by Pass 1 (which actually runs each example).

## Methodology

Each pass follows the same three-step pattern:

1. **Audit.** Map existing test/example coverage of the documented features for this surface. Use the `Explore` subagent for breadth; ask for a coverage table. See "Subagent prompt template" appendix.
2. **Probe.** For anything flagged as untested or unclear, verify directly — either via a small scratch test or by running the example. Don't trust the absence of bugs; check.
3. **File.** Report findings; file issues following the project style (scope + concrete files + acceptance criteria + out-of-scope). One issue per substantive finding; small related items can batch into a tracker.

### Before you start

- [ ] `git status` clean — no uncommitted scratch files that could leak into QA findings
- [ ] `git pull` latest from main
- [ ] `mvn install -DskipTests` from repo root completes cleanly (installs framework jars to local repo so per-example `exec:java` works)
- [ ] `docker ps` succeeds (for Pass 1 Kafka example) — if absent, scope the Kafka step down to "build only"
- [ ] Note current branch state in your task log so a partial run can be resumed

### Verify before filing as a `bug`

**This is the single most important guard against false positives.** Before filing label=`bug`:

- Reproduce with a minimal scratch test (compile-testing fixture or 10-line `Main`)
- Capture the actual observed behavior verbatim
- Confirm the documented contract is what you think it is

If you can't reproduce in isolation, the finding is a `documentation` or `enhancement` issue, not a `bug`. A "test coverage gap" is also `enhancement` or `bug` only if you verified the underlying code is broken — not if the check might fire but has no regression net (that's a test gap, not a bug).

Concrete example from 2026-05-23: the missing-interface check (Pass 4) looked at first like a test-coverage gap (which would be `enhancement`). The scratch test showed the check doesn't fire at all → real bug → filed as `bug` (#164).

### Bug-severity heuristic (for milestone choice)

- **Critical (gate release / current phase):** user-facing example doesn't run, documented contract silently violated, framework cascades on user input, security-relevant. → Phase 3 if shippable in the window, else surface separately.
- **Non-critical (next phase or unmilestoned):** message-quality regressions, documentation drift, missing tests for features that demonstrably work, polish.
- **Decision-required:** finding that depends on a product call (deprecate vs add example, change vs document existing behavior). File with label=`question` or leave unmilestoned so it surfaces in triage.

### What NOT to file

Things that look like Tiko bugs but aren't:

- **Windows console mojibake** (`?` for unicode glyphs, `kv?` for Czech "květen"). Platform encoding issue, not Tiko's fault.
- **Locale rendering in JUL log output.** Same: JDK + console codepage.
- **Mockito agent warning** ("dynamic agent loading"). JDK 21 noise, not Tiko.
- **Missing Docker.** Skip the Pass 1 Kafka runtime portion and document the manual run sequence; don't file.
- **Spotless formatting "changes."** Run `mvn spotless:apply`.
- **Pre-existing roadmap items** that are unshipped (Phase 5 resiliency, Phase 6 transports). Don't QA what doesn't exist.

### Issue body template

Use this skeleton for every filed issue. Project style is "scope + concrete files + acceptance + out-of-scope, rationale lives in linked predecessor issues."

```markdown
Follow-up to <predecessor issue or QA pass>.

## Scope
<one paragraph: what's wrong / what needs adding, with reproducer if applicable>

## Files
- `path/to/file.ext` — what changes
- `path/to/new-file.ext` (new) — what it contains

## Acceptance
- <observable outcome 1>
- <observable outcome 2>
- <error message shape / format requirement if relevant>

## Out of scope
- <related concern that lives in a sibling issue>
- <future expansion explicitly excluded>
```

### Tooling notes

- **Maven.** From the repo root, `mvn install -DskipTests` once installs framework jars to the local repo. After that, run examples directly via their poms: `mvn -f tiko-examples/N/pom.xml exec:java -Dexec.mainClass=...`. Going through the parent reactor with `-pl X exec:java` runs `exec:java` against the wrong project.
- **Surefire / Failsafe.** Per CLAUDE.md: `*Test` → surefire (unit); `*IT` → failsafe (integration). Several example modules name integration tests `*Test` and don't configure failsafe — verify your test files use the right suffix, and that the pom wires the right plugin.
- **Scratch QA test pattern.** When probing compile-time errors, write a temporary test in `tiko-processor/src/test/java/io/tiko/processor/qa/` using Google `compile-testing`. Iterate one source per case, print diagnostics to stdout, run with `mvn test -Dtest=YourTest -Dsurefire.useFile=false`, then `grep "^(=====|CASE|STATUS|----|\[ERROR\]|\[WARNING\])"` to extract them. Run `mvn spotless:apply` after writing (Palantir formatter is strict on line wrapping). **Delete the file when done** — scratch must not commit.
- **GitHub milestones.** `gh issue create --milestone N` expects the milestone *title*, not the number, and breaks on the em-dash in titles like "Phase 3 — Onboarding & tooling". Use the two-round pattern: create without `--milestone`, then `gh api repos/:owner/:repo/issues/{N} -X PATCH -F milestone={number}` to attach.

---

## Pass 1 — Examples sweep

**Goal:** every example builds, runs, and matches what its README (and the parent index at `tiko-examples/README.md`) claims.

**Method:** for each example, build it, run it (where runnable), compare output to docs. For multi-module examples, build via `-am` then exec from the leaf pom. For Kafka / Testcontainers, verify Docker availability first; if absent, document and skip the runtime portion.

### Current example inventory

| # | Module | Runnable | Claim source | Notes |
|---|---|---|---|---|
| 01 | `01_basic_di` | Main + tests | parent README + per-example README | Per-example README is stale (issue #153) |
| 02 | `02_config` | Main | parent README | Single-line output |
| 03 | `03_events` | Main + tests | parent README | Event chaining + lifecycle |
| 04 | `04_api_impl` | Main (multi-module) | parent README | Verify runtime-scope dep via `dependency:tree` |
| 05 | `05_multi_module` | Main (multi-module) | parent README | AggregatingContainer |
| 06 | `06_config_multi_module` | Main (multi-module) | parent README | Config defaults baked into modules |
| 07 | `07_async_start` | Main | parent README | `@EventHandler(async=true)` |
| 08 | `08_kafka_order_warehouse` | Multi-process + Testcontainers IT | parent + per-example README | Needs Docker |
| 09 | `09_http_javalin` | Main + tests + curl | per-example README | Boots Javalin |
| 10 | `10_persistence_jdbc` | Tests only (no Main today) | parent README | H2 + Hikari + JDBC tx |
| 11 | `11_custom_logger` | Main | per-example README | Routes JUL via slf4j+logback |
| 12 | `12_testing` | Tests only | per-example README | `@TikoTest` extension |
| 13 | `13_mcp_introspection` | Built + MCP server | per-example README | MCP tools introspect this app |

### Scenarios

- Build: `mvn install -DskipTests` at root once, then per-example.
- For each example with a `Main`: run via `exec:java`, capture output, compare to README's documented output or behavior.
- For each example with a per-example README: cross-check claims against actual code (grep for the features claimed).
- For each multi-module example: verify `dependency:tree` matches the described topology.
- For test-only examples (10, 12): `mvn test` and verify all pass.
- For examples needing external services (08 Kafka): check Docker availability, run the IT if possible, else document the manual run sequence.

### How to extend

When a new example is added under `tiko-examples/N_<name>/`:
1. Add a row to the inventory table above.
2. Add the example's main class FQN and any special run notes.
3. Run it as a Pass-1 scenario the next time QA runs.

---

## Pass 2 — Scope matrix + compile-time validation (test coverage)

**Goal:** every cross-scope injection combination has a focused test, and every documented compile-time check has a regression net.

**Relationship to Pass 4:** Pass 2 asks "**does a test exist** that pins this check / cell?" Pass 4 asks "**what does the actual error message look like** when the check fires?" Same set of validation rules; different questions. Run both — a check can have a passing test (Pass 2 ✓) but emit a terrible error message (Pass 4 ✗), or vice versa.

**Method:** audit `tiko-processor/src/test/` for coverage of each cell + negative path. For gaps, write a small `compile-testing` fixture asserting the expected outcome.

### Scope cell matrix (4 × 4 = 16 cells)

For each cell, verify: compiles when allowed, generates a proxy when required, errors cleanly when forbidden, runtime delegates correctly.

| From \ To | SINGLETON | REQUEST | EVENT | PROTOTYPE |
|---|---|---|---|---|
| SINGLETON | direct | proxy (interface req.) | proxy (interface req.) | direct, fresh each call |
| REQUEST | direct | direct | proxy (interface req.) | direct, fresh each call |
| EVENT | direct | direct | direct | direct, fresh each call |
| PROTOTYPE | direct | direct | direct | direct, fresh each call |

### Negative paths (coverage requirement)

Each documented compile-time check should have a focused test (Pass 2's concern). Pass 4 separately verifies the message quality.

- Missing dependency (no provider)
- Circular dependency (2-deep + N-deep + `Provider<T>` escape)
- Missing interface where proxy is required
- Ambiguous qualifier (multiple impls, no `@Named`)
- `@Produces` signature violations (void return, invalid type)
- `@Inject` on field / setter / non-constructor
- `@Inject` constructor on non-`@Component` class

### How to extend

- New scope added → add row + column to the matrix; specify behavior of each new cell; file tests.
- New `@Component`-level validation rule (e.g. `expose = {...}`, `forbidProfiles = {...}`) → add a "negative paths" entry; file a test; **also add a row to Pass 4's reproducer table** so its error message is graded.
- New annotation that the processor reads → add a "validation" check for misuse; mirror in Pass 4.

---

## Pass 3 — Event chain semantics

**Goal:** every `@EventTrigger` feature works as documented, including failure semantics and edge cases.

**Method:** audit `tiko-examples/01_basic_di/src/test/java/.../EventTriggerTest.java` and related processor tests. Verify each of the 10 documented features has a focused positive test; identify negative-path gaps.

### Feature matrix

| # | Feature | What to verify | Test coverage | Demo (Pass 6) |
|---|---|---|---|---|
| 1 | Return-as-payload | Handler return value becomes payload of triggered event | required | required |
| 2 | Multiple `@EventTrigger` (`@EventTriggers` container) | Same return value publishes N times | required | required |
| 3 | `async = true` | Trigger publishes on a different thread | required | required |
| 4 | `spread = true` | Collection/array/Iterable fans out per element; empty → 0 events; null element → documented behavior; Map → rejected or documented | required | required |
| 5 | Guards (`EventTriggerGuard`) | True → publish; false → suppress; multiple guards AND with short-circuit in source order | required | required |
| 6 | `Event<T>` origin chain | `getOriginChain()` returns full lineage; `findInChain(Class)` returns first match; not-found → empty Optional | required | required |
| 7 | "Trigger only on successful return" | Handler exception → triggered event NOT published; sync + async forms; mid-chain | required | optional |
| 8 | Optional `Event<?>` second param | Handler signature `(EventType, Event<?>)` works; param is optional | required | required |
| 9 | `EventCallback<T>` | Functional-interface subscription via `EventBus.subscribe` | required | optional |
| 10 | Lifecycle events | All 6 (Application/Request/Event Started/Ending) fire with correct payload — overlaps Pass 5 | required | required |

The "Test coverage" column is Pass 3's concern (was a test written?). The "Demo (Pass 6)" column tracks whether an example actually exercises the feature in `main` code — Pass 6 fills this in. A row can be tested but undemoed; both matter for different reasons.

### How to extend

- New `@EventTrigger` parameter → add a row; specify happy path + 2-3 edge cases for the Test column; mark Demo expectation.
- New programmatic-subscription API → add a row alongside `EventCallback`.

---

## Pass 4 — Compile-time error UX (message quality)

**Goal:** every Tiko-emitted error follows CLAUDE.md's "show problematic location + explain what's wrong + suggest at least one fix" format.

**Relationship to Pass 2:** Pass 4 grades the message text the check produces; Pass 2 grades whether the check has a regression test. Both must pass for a validation rule to be release-ready.

**Method:** write a scratch `compile-testing` probe that triggers each error category, captures the diagnostic text, and grades it. See "Scratch QA test pattern" under Methodology.

### Error categories to probe

For each, expect: source location (`file:line`), one clean Tiko message (not a cascade of generated-code errors), and a "Suggested fixes:" section.

| # | Category | Reproducer shape |
|---|---|---|
| 1 | Missing dependency | `@Component A` injects non-`@Component B` |
| 2 | Circular dependency `A→B→A` | Two `@Component`s injecting each other |
| 3 | Missing interface for proxy | SINGLETON injects REQUEST-scoped concrete class |
| 4 | Ambiguous interface | Two `@Component`s implement same interface, no `@Named`; a third injects the interface |
| 5 | Bad `@Produces` signature | Void return, primitive return (if disallowed), wildcard generic |
| 6 | `@Inject` on field | `@Inject Repository repo;` |
| 7 | `@Inject` on setter | `@Inject public void setRepo(Repository)` |
| 8 | `@Configuration` issues | Required field with no default; bad `@Default` value for type; bad `@Key` target |

### Format checklist (per error)

- [ ] Source location (`file:line`) printed first
- [ ] Plain-English explanation of what's wrong
- [ ] "Suggested fixes:" numbered list, at least one entry
- [ ] No cascade of generated-code errors (one clean error per violation)
- [ ] No duplicate emission (one error per violation, not one per consumer)
- [ ] For cycles: full cycle path (`A → B → C → A`), not just one node; `Provider<T>` mentioned as the escape valve
- [ ] No generic noise trailers (`Tiko DI: Validation failed!`-style)

### Grading rubric

Apply consistently across all error categories so a future operator's grades are comparable.

- **A** — Full CLAUDE.md format: location + explanation + numbered fixes + no noise.
- **B** — Location + explanation, but no fix suggestion. Reader knows what + where, not how to resolve.
- **C** — Explanation only. Names the type or rule but no source location; no fix.
- **D** — Bare violation announcement. Names the offending element but no context, no fix.
- **F** — Framework cascades on the user (generated-code javac errors leaking through), OR check doesn't fire at all → real bug, file as `bug` not message-quality.

### How to extend

When a new validation check is added to the processor:
1. Add a row to the reproducer table above.
2. Run Pass 4 to grade the message.
3. **Also add the check to Pass 2's "Negative paths" list** so the test-coverage requirement is captured.

---

## Pass 5 — Lifecycle events + configuration (polish)

**Goal:** lifecycle events fire at the right phase, in the right order, with correct payloads; configuration binds cleanly and fails informatively.

**Note:** lifecycle and config are independent surfaces grouped only because each is individually small. Treat the two sub-sections below as separate mini-passes; a change that touches only lifecycle doesn't need to revisit config (and vice versa).

### Lifecycle events

| Event | Payload | Ordering contract |
|---|---|---|
| `ApplicationStartedEvent` | `Instant timestamp` | Fires AFTER all `@PostConstruct` complete |
| `ApplicationEndingEvent` | `Instant timestamp`, `Duration uptime` | Fires BEFORE any `@PreDestroy` |
| `RequestStartedEvent` | `String requestId`, `Instant timestamp` | Fires BEFORE any user `@EventHandler` in scope |
| `RequestEndingEvent` | `String requestId`, `Instant timestamp`, `Duration duration` | Fires AFTER all user handlers complete (including async drain) |
| `EventStartedEvent` | `String eventId`, `Instant timestamp` | Same pair semantics as Request |
| `EventEndingEvent` | `String eventId`, `Instant timestamp`, `Duration duration` | Same pair semantics as Request |

Plus:
- Nested scopes (one REQUEST containing N EVENTs) — each pair properly nested.
- Idempotency across multiple `container.shutdown()` calls.
- Timestamp monotonicity within a single container instance.

### Configuration

| Feature | Verify |
|---|---|
| `@Configuration(prefix=...)` record binding | Happy path |
| `@Default("...")` | Used when field absent; rejected at compile time when value can't be parsed for the target type |
| `@Key("yaml-key")` | Field bound from a non-default YAML key |
| Nested records | Plain record as a field of a `@Configuration` record binds recursively |
| `${VAR:default}` interpolation | Set/unset paths; nested `${A:${B}}` — behavior pinned |
| Layered `ConfigSources` | Later sources override earlier on duplicate keys |
| Strict-mode boot failure | Bad config → `ConfigValidationException` from `Tiko.create()`, not later at first access |

### Error UX

| Failure mode | Expected error |
|---|---|
| Malformed YAML (syntax error) | Tiko-shaped error pointing at file + line, not a raw SnakeYAML stack trace |
| Required field missing | One error per missing field, accumulated; names the YAML key (if `@Key` present, use the YAML key, not the field name) |
| Type mismatch (string → int) | One error naming the path and the conversion failure |
| Multiple errors | Single exception carrying all errors (per the "fail boot, not first access" contract) |

### How to extend

- New lifecycle event → add row to event table; specify ordering contract.
- New config annotation or feature → add row to features table; specify happy path + error path.

---

## Pass 6 — Feature-to-example coverage

**Goal:** every shipped public feature has at least one example module that demonstrates it in `main` code (not just framework tests).

**Method:** enumerate annotations + public types + documented behaviors; for each, identify the demonstrating example. Distinguish DEMONSTRATED (main code uses + exercises), MENTIONED (imported but not really exercised, or test-only), GAP (no example at all). Use the `Explore` subagent for breadth.

### What counts as a "shipped feature"

1. Every annotation in `tiko-api/src/main/java/io/tiko/annotations/` (`@Component`, `@Inject`, `@Named`, `@Produces`, `@PostConstruct`, `@PreDestroy`, `@EventHandler`, `@EventTrigger`, `@EventTriggers`, `@Configuration`, `@Default`, `@Key`, `@TestComponent`, `@Pick`, plus any added later)
2. Every public type in `tiko-api/` (`Container`, `Provider`, `EventBus`, `Event<T>`, `EventCallback<T>`, `EventTriggerGuard`, `ErrorHandler`, `Scope`, lifecycle events under `io.tiko.events`, `TikoOptions`)
3. Documented non-annotation behaviors from CLAUDE.md: cross-scope proxying, `container.pick(Class)`, `runInRequestScope`/`runInEventScope` (+ `supplyIn*` variants), `AggregatingContainer`, YAML config layering, `${VAR:default}` interpolation, strict-mode config-fail-at-boot, profile-based selection, `AutoCloseable` lifecycle, `expose = {...}` restrictions, `TikoOptions` builder including custom `ErrorHandler`/`EventExecutor`

### Reporting categories

- **DEMONSTRATED** — example imports + uses the feature in main code; output or assertions exercise it
- **MENTIONED** — example imports the type but doesn't really exercise it, or use is test-only
- **GAP** — no example uses it at all (framework's own tests don't count)

### How to extend

**When a new annotation, public type, or documented behavior is added to the framework:**
1. Update the "What counts" lists above.
2. Add the feature to whichever example demonstrates it; if no example does, file a `documentation`-labeled issue under "examples coverage gap."

**When a new example module is added:**
1. Pass 1's inventory table gets a row (see Pass 1).
2. Run Pass 6 — it may close gaps for features the new example demonstrates.
3. Pass 3's feature matrix may have its "Demo (Pass 6)" column updated for any `@EventTrigger`-related features.

---

## Pass 7 — MCP introspection

**Goal:** every MCP tool returns the response shape its README documents, handles edge cases cleanly, and stays consistent with the framework's data model.

**Method:** invoke each tool with realistic input + edge cases; compare against the documented response samples in `tiko-examples/13_mcp_introspection/README.md`.

### Scenarios per tool

For each tool registered in `tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java`:

- **Happy path:** realistic input, response matches README sample for the example app
- **Unknown identifier:** non-existent component FQN, unknown event type, unknown config prefix → clean error, fuzzy-match suggestion where applicable
- **Empty filter:** valid filter value with no matches → empty list, NOT an error
- **Derived data shape:** for tools emitting JSON Schema (`get_config_schema`), JSON-validate the output; for tools emitting trees (`explain_wiring`), verify the tree is well-formed
- **Cross-tool consistency:** identifiers used as filter values in one tool match identifiers emitted by another (e.g. `list_events`'s `eventType` keys should be usable as `list_events {eventType: ...}` filter values)
- **After rebuild:** the MCP server is process-cached. `claude mcp restart tiko` (or restart Claude Code / Cursor) before re-querying. A `reload` tool is filed as #145.

### How to extend

When a new MCP tool is added to `tiko-mcp/.../tools/`:
1. Add a sample request + response to `tiko-examples/13_mcp_introspection/README.md`.
2. Run the scenarios above against it.
3. If the tool emits a new data shape (new JSON structure not previously documented), add a "derived data shape" entry to the scenarios.

---

## Out of scope (deliberately)

- **Unit test coverage** — `mvn test` is a different lens; if a unit-test gap is the finding, file it as a `test` issue but don't conflate with playbook scenarios.
- **Performance.** Cold-start harness exists separately.
- **Security review.** A separate skill (`/security-review`) exists.
- **Unshipped features.** Phase 5 (resiliency, #106–#111) and Phase 6 (distributed transports, #117–#120) — don't QA what doesn't exist yet.

---

## Past runs

### 2026-05-23 — full QA pass (Passes 1–7)

- **34 issues filed** (#140–#173, all consecutive)
- **4 real behavior bugs found:**
  - #149 — `08_kafka_order_warehouse/e2e` silently doesn't run its `*IT` test (failsafe not configured)
  - #150 — `11_custom_logger` doesn't deliver the documented logback routing
  - #164 — processor missing-interface check doesn't fire (#159 test gap was hiding a real bug)
  - #165 — `@Produces` signature unvalidated; void return cascades into 6 javac errors
- **MCP server (Pass 7):** clean after a fix loop with the implementing agent — all four tools deliver documented behavior, edge cases handled.
- **Headline observation:** compile-time validation layer (Tiko's strongest pitch) is the most undertested — three shipped checks had zero regression nets, and one of them was hiding a real bug.
- **Phase distribution:** 18 issues to Phase 3 (onboarding/examples + MCP enhancements), 12 to Phase 4 (test coverage + processor bugs + error UX), 4 unmilestoned (feature-example gaps awaiting product decisions).

When updating this playbook, append a new dated subsection here. Future runs see what already exists and don't duplicate work.

---

## Appendix: Subagent prompt template

For the audit step of any pass, the prompt that worked consistently in 2026-05-23:

```
I'm QA-ing the Tiko DI framework. Codebase root: <absolute path>.

I need a coverage map for <surface>. Tests live under <module>/src/test/java/.

For each of the <N> <items> listed below, tell me:
1. Is there a focused test? Name file:method.
2. If multiple tests exist for one item, note that.
3. If the only "coverage" is incidental — a test of another feature that
   happens to touch this one — flag as `incidental` and name which test.
4. If completely untested, mark GAP.

Items to audit:
- <item 1: clear, single sentence>
- <item 2>
- ...

Behavioral nuances to specifically probe:
- <nuance 1: e.g. edge case, negative path>
- ...

Output format: markdown table (Item | Status: tested/incidental/GAP | File:method) + a
short bullet list of behavioral nuances. Under <N> words total.

Don't speculate — if you can't find a test, say GAP. Don't paper over.
```

Adapt categories per pass (Pass 6 uses DEMONSTRATED/MENTIONED/GAP instead of tested/incidental/GAP). Word limit keeps subagent reports skim-able; 600–800 has worked.
