---
paths:
  - "**/src/test/**"
---

# Tests

## Testing Strategy

- **Framework: JUnit 5 only.** No JUnit 4, no mixing. AssertJ for assertions
  (no Hamcrest, no JUnit's `Assertions`).
- **Class naming:** `xxxTest` for unit tests, `xxxIT` for integration tests.
  Surefire runs `*Test` at the `test` phase (fast lane: `mvn test`). Failsafe runs
  `*IT` at the `integration-test` / `verify` phases (`mvn verify`). Both runners are
  wired in the root `pom.xml` and inherited by every module — no per-module plumbing.
  A misnamed `*IT.java` won't be discovered by surefire, so it will only run under
  `mvn verify`; check the output to confirm your IT actually ran.
- **Method naming: camelCase, no underscores.** New tests use
  `applicationStartedPublishedOnceAfterContainerBoot`, not
  `application_started_published_once`. Existing snake_case tests stay until
  they're touched for other reasons; don't churn the tree just to rename.
- **No `@Disabled` tests.** Fix or delete.
- **No bare `Thread.sleep`.** Either remove if the assertion is trivially
  satisfied (e.g. `>= Duration.ZERO`), or use Awaitility:
  `await().atMost(...).until(...)` for condition polls,
  `await().pollDelay(...).until(() -> true)` for intentional pauses where no
  observable signal exists.
- **Parameterize multi-assertion methods.** Multiple `assertThat` calls in one
  test body covering distinct cases → `@ParameterizedTest` + `@MethodSource`
  returning `Arguments.of(name, ...)` so failures report which row failed.
- **Annotation-processor coverage:** use Google's `compile-testing` to compile
  sample sources and assert on generated output. Negative paths (invalid scopes,
  missing deps, circular deps, bad config) need explicit tests.
- **No test side effects bleed across tests.** Reset system properties; use
  `@TempDir` for filesystem fixtures; no ThreadLocal contamination.
- **Verify error messages are helpful** — see "Error Message Format" above.
