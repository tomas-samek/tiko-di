# MCP Introspection Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the six MCP introspection enhancements filed as #22 follow-ups (#140, #141, #142, #143, #144, #145) — `reload`, `find_dependents`, `list_wiring_errors`, surface `@Produces`, profile-aware queries, and `trace_event_flow`.

**Architecture:** Each enhancement is a small additive change to `tiko-mcp` and (where needed) `tiko-processor`. New tools live in `tiko-mcp/src/main/java/io/tiko/mcp/tools/`. Processor-side additions extend `topology.json` (additive — `schemaVersion: 1` remains; doc `docs/topology-schema.md` gets new optional fields) and add a sibling `wiring-errors.json` artifact. Each issue ships as its own PR off `worktree-phase3-followups` (one branch per issue per `feedback_branch_per_change` and `feedback_pr_descriptions_scoped`).

**Tech Stack:** Java 21, JUnit 5, AssertJ, Maven 3, MCP Java SDK (`io.modelcontextprotocol.sdk`), `tiko-processor` annotation processor.

**Scope explicitly excluded:** Examples QA backlog (#149–#157, "Pass 1 QA" follow-ups). Per user, more QA passes are still running on `main` and the backlog is expected to grow. That batch gets a separate plan once QA settles.

---

## File Structure

**New files (per task):**

```
tiko-mcp/src/main/java/io/tiko/mcp/tools/
├── ReloadTool.java                 # Task 1 (#145)
├── ListWiringErrorsTool.java       # Task 3 (#142)
├── FindDependentsTool.java         # Task 4 (#141)
├── TraceEventFlowTool.java         # Task 5 (#140)
└── ListProfileConflictsTool.java   # Task 6 (#144)

tiko-mcp/src/test/java/io/tiko/mcp/tools/
├── ReloadToolTest.java
├── ListWiringErrorsToolTest.java
├── FindDependentsToolTest.java
├── TraceEventFlowToolTest.java
└── ListProfileConflictsToolTest.java

tiko-processor/src/main/java/io/tiko/processor/topology/
└── WiringErrorsWriter.java         # Task 3 (#142)

tiko-processor/src/main/java/io/tiko/processor/model/
└── WiringError.java                # Task 3 (#142) — sealed record
```

**Modified files (per task):**

- `tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java` — Tasks 1, 3 (reload; load `wiring-errors.json`)
- `tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java` — Tasks 1, 3, 4, 5, 6 (register new tools)
- `tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java` — Tasks 1, 3, 4, 5, 6 (tool specs + schemas)
- `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java` — Tasks 2, 6 (surface PRODUCED; profile filter)
- `tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java` — Tasks 2, 6 (walk producer edges; profile filter)
- `tiko-processor/src/main/java/io/tiko/processor/topology/TopologyWriter.java` — Task 2 (add `producedBy` echo / scope of producers)
- `tiko-examples/13_mcp_introspection/...` — Tasks 2, 5, 6 (add `@Produces` bean; async trigger; profile conflict pair)
- `tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java` — Tasks 1, 3, 4, 5, 6 (extend `EXPECTED_TOOLS`)
- `docs/topology-schema.md` — Task 3 (document `wiring-errors.json`)

**Task ordering rationale:**

1. **#145 reload** first — small, foundational, lets later tasks iterate without restarting the server in manual QA.
2. **#143 surface @Produces** before tools that walk producer edges (#141 `find_dependents`, #140 `trace_event_flow` may reference producers).
3. **#142 list_wiring_errors** — processor-side artifact, independent of MCP-only tasks.
4. **#141 find_dependents** — pure MCP, reverse-index.
5. **#140 trace_event_flow** — needs `eventTriggers[]` already complete (it is; topology already emits `eventName`, `async`, `spread`, `guards`).
6. **#144 profile filters + list_profile_conflicts** — pure MCP, finishes the batch.

---

## Task 1: #145 — `reload` tool

**GitHub:** #145

**Branch:** `mcp-reload-tool` off `worktree-phase3-followups`

**Files:**
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java` (add mutable reload path)
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ReloadTool.java`
- Create: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ReloadToolTest.java`
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java`
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java`
- Modify: `tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java`

### Steps

- [ ] **Step 1.1: Branch from worktree HEAD**

  ```bash
  git checkout -b mcp-reload-tool
  ```

- [ ] **Step 1.2: Write failing test for `TopologyStore.reload()`**

  Append to `tiko-mcp/src/test/java/io/tiko/mcp/TopologyStoreTest.java` (existing file; add a new `@Test` method, do not duplicate the class):

  ```java
  @Test
  void reloadPicksUpFilesystemChanges(@TempDir Path root) throws Exception {
      var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
      Files.createDirectories(f.getParent());
      Files.writeString(f, """
              {"schemaVersion":1,"module":"m",
               "components":[{"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]}],
               "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
              """, StandardCharsets.UTF_8);

      var store = TopologyStore.loadFrom(root);
      assertThat(store.components()).hasSize(1);

      Files.writeString(f, """
              {"schemaVersion":1,"module":"m",
               "components":[
                 {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                 {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":[]}
               ],
               "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
              """, StandardCharsets.UTF_8);

      store.reload();
      assertThat(store.components()).hasSize(2);
  }
  ```

- [ ] **Step 1.3: Run test to verify it fails**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=TopologyStoreTest#reloadPicksUpFilesystemChanges
  ```

  Expected: FAIL — `reload()` method does not exist on `TopologyStore`.

- [ ] **Step 1.4: Make `TopologyStore` reloadable**

  In `tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java`:
  - Change the six collection fields from `final` to non-final (or keep `final` and have `reload()` mutate via `clear()`+repopulate — pick the latter to keep external callers' references valid).
  - Capture the `projectRoot` in a new `final Path projectRoot` field set by a new private constructor used by both `loadFrom` and `reload`.
  - Add a public method:

  ```java
  /** Re-reads topology.json / config-schema.json / wiring-errors.json under projectRoot.
   *  Mutates this store in place so existing tool instances see the new data. */
  public synchronized void reload() {
      var fresh = loadFrom(projectRoot);
      this.components.clear();   this.components.addAll(fresh.components);
      this.factoryMethods.clear(); this.factoryMethods.addAll(fresh.factoryMethods);
      this.eventHandlers.clear();  this.eventHandlers.addAll(fresh.eventHandlers);
      this.eventTriggers.clear();  this.eventTriggers.addAll(fresh.eventTriggers);
      this.configurations.clear(); this.configurations.addAll(fresh.configurations);
      this.configSchema = fresh.configSchema;
      this.loadedAt = Instant.now();
  }
  ```

  Add `private Instant loadedAt = Instant.now();` field and a public `Instant loadedAt()` accessor (used by Step 1.7 below for the reload response payload). Set `loadedAt` inside `loadFrom` too.

- [ ] **Step 1.5: Run test to verify it passes**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=TopologyStoreTest#reloadPicksUpFilesystemChanges
  ```

  Expected: PASS.

- [ ] **Step 1.6: Write failing test for `ReloadTool`**

  Create `tiko-mcp/src/test/java/io/tiko/mcp/tools/ReloadToolTest.java`:

  ```java
  package io.tiko.mcp.tools;

  import static org.assertj.core.api.Assertions.assertThat;

  import io.tiko.mcp.TopologyStore;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.Map;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  class ReloadToolTest {

      @Test
      void reloadReturnsReloadedTrueAndTimestamp(@TempDir Path root) throws Exception {
          var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
          Files.createDirectories(f.getParent());
          Files.writeString(f, """
                  {"schemaVersion":1,"module":"m",
                   "components":[{"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]}],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """, StandardCharsets.UTF_8);
          var store = TopologyStore.loadFrom(root);
          var tool = new ReloadTool(store);

          Map<String, Object> result = tool.execute(Map.of());

          assertThat(result.get("reloaded")).isEqualTo(Boolean.TRUE);
          assertThat(result.get("topologyTimestamp")).isInstanceOf(String.class);
          assertThat((String) result.get("topologyTimestamp")).isNotBlank();
      }

      @Test
      void reloadPicksUpAddedComponent(@TempDir Path root) throws Exception {
          var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
          Files.createDirectories(f.getParent());
          Files.writeString(f, """
                  {"schemaVersion":1,"module":"m",
                   "components":[{"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]}],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """, StandardCharsets.UTF_8);
          var store = TopologyStore.loadFrom(root);
          var tool = new ReloadTool(store);

          Files.writeString(f, """
                  {"schemaVersion":1,"module":"m",
                   "components":[
                     {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                     {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":[]}],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """, StandardCharsets.UTF_8);

          tool.execute(Map.of());
          assertThat(store.components()).hasSize(2);
      }
  }
  ```

- [ ] **Step 1.7: Implement `ReloadTool`**

  Create `tiko-mcp/src/main/java/io/tiko/mcp/tools/ReloadTool.java`:

  ```java
  package io.tiko.mcp.tools;

  import io.tiko.mcp.TopologyStore;
  import java.util.LinkedHashMap;
  import java.util.Map;

  /**
   * MCP tool: re-reads META-INF/tiko/*.json from disk into the in-memory store.
   *
   * <p>Today the topology is loaded once at server boot. An agent that edits source,
   * runs {@code mvn compile}, and re-queries gets stale answers until the server
   * restarts. Calling {@code reload} after a rebuild refreshes the store in place.
   * Takes no arguments.
   */
  public final class ReloadTool {

      public static final String NAME = "reload";

      private final TopologyStore store;

      public ReloadTool(TopologyStore store) {
          this.store = store;
      }

      public Map<String, Object> execute(Map<String, Object> args) {
          store.reload();
          var out = new LinkedHashMap<String, Object>();
          out.put("reloaded", Boolean.TRUE);
          out.put("topologyTimestamp", store.loadedAt().toString());
          return out;
      }
  }
  ```

- [ ] **Step 1.8: Run unit tests to verify both pass**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=ReloadToolTest
  ```

  Expected: 2 tests PASS.

- [ ] **Step 1.9: Register `ReloadTool` in `TikoMcpServer`**

  In `tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java`:
  - Import `ReloadTool`.
  - Add `var reload = new ReloadTool(store);` after the four existing tool instantiations.
  - Add `reload` as a positional argument to the `McpStdioBridge` constructor call (new bridge takes a 5th arg).

- [ ] **Step 1.10: Register the tool spec in `McpStdioBridge`**

  In `tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java`:
  - Add `private final ReloadTool reload;` field plus constructor param.
  - Add `var reloadSchema = """{"type":"object","properties":{}}""";`
  - Add a fifth `spec(...)` argument inside `.tools(...)`:

  ```java
  spec(mapper, ReloadTool.NAME, "Reload Tiko topology from disk", reloadSchema, reload::execute)
  ```

- [ ] **Step 1.11: Extend `TikoMcpServerSubprocessIT` to expect the new tool**

  In `tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java`:
  - Add `"reload"` to `EXPECTED_TOOLS`.
  - Add `.contains("reload")` to the final assertion chain.

- [ ] **Step 1.12: Build, test, format**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp clean test
  ```

  Expected: BUILD SUCCESS, all `tiko-mcp` tests green.

- [ ] **Step 1.13: Commit and open PR**

  ```bash
  git add tiko-mcp/
  git commit -m "feat(mcp): add reload tool to refresh topology from disk (#145)"
  git push -u origin mcp-reload-tool
  "C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(mcp): add reload tool (#145)" --body "Closes #145. Adds the \`reload\` MCP tool (no args) that re-reads META-INF/tiko/*.json from disk into TopologyStore so an agent can iterate after \`mvn compile\` without restarting the server. Returns \`{reloaded: true, topologyTimestamp: \"...\"}\`."
  ```

  Branch protection blocks `--admin` merges — user merges in UI, agent does cleanup (per `feedback_pr_merge_user_action`).

---

## Task 2: #143 — surface `@Produces` factory methods

**GitHub:** #143

**Branch:** `mcp-surface-produces` off `worktree-phase3-followups` (after Task 1 merges back to `main`; rebase if needed)

**Background:** `factoryMethods[]` already ships in `topology.json` (see `TopologyWriter.writeFactoryMethods`). What's missing is:
1. `list_components` does not include `@Produces` outputs (only `@Component` classes).
2. `explain_wiring` does not follow producer edges — an `@Inject` of a `@Produces`-only type returns null / fails the did-you-mean.

**Files:**
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java`
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java`
- Modify: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListComponentsToolTest.java`
- Modify: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ExplainWiringToolTest.java`
- Modify: `tiko-examples/13_mcp_introspection/src/main/java/example/DbConfig.java` and add `example/Producers.java`
- Modify: `docs/topology-schema.md` (mention how PRODUCED surfaces via the MCP layer; no schema change)

### Steps

- [ ] **Step 2.1: Branch**

  ```bash
  git checkout worktree-phase3-followups
  git pull --ff-only
  git checkout -b mcp-surface-produces
  ```

- [ ] **Step 2.2: Write failing test — `list_components` includes PRODUCED entries**

  Append to `ListComponentsToolTest.java`:

  ```java
  @Test
  void includesProducedFactoryOutputs(@TempDir Path root) throws Exception {
      var store = storeWith(root, """
              {"schemaVersion":1,"module":"m",
               "components":[
                 {"qualifiedName":"example.Producers","scope":"SINGLETON","interfaces":[]}
               ],
               "factoryMethods":[
                 {"declaringClass":"example.Producers","methodName":"db",
                  "returnType":"javax.sql.DataSource","scope":"SINGLETON",
                  "qualifier":"primary","profiles":[],"static":false,
                  "autoCloseable":true,"requiresProxy":false,
                  "constructorDependencies":[]}
               ],
               "eventHandlers":[],"eventTriggers":[],"configurations":[]}
              """);
      var tool = new ListComponentsTool(store);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> result =
              (List<Map<String, Object>>) tool.execute(Map.of()).get("components");

      assertThat(result).hasSize(2);
      assertThat(result).anySatisfy(c -> {
          assertThat(c.get("kind")).isEqualTo("PRODUCED");
          assertThat(c.get("qualifiedName")).isEqualTo("javax.sql.DataSource");
          assertThat(c.get("qualifier")).isEqualTo("primary");
          @SuppressWarnings("unchecked")
          var pb = (Map<String, Object>) c.get("producedBy");
          assertThat(pb.get("componentFqn")).isEqualTo("example.Producers");
          assertThat(pb.get("methodName")).isEqualTo("db");
          assertThat(pb.get("isStatic")).isEqualTo(Boolean.FALSE);
      });
  }
  ```

- [ ] **Step 2.3: Run test to verify it fails**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=ListComponentsToolTest#includesProducedFactoryOutputs
  ```

  Expected: FAIL — only 1 entry returned (just the `Producers` `@Component`).

- [ ] **Step 2.4: Extend `ListComponentsTool` to project factory methods as PRODUCED entries**

  In `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java`, after building `filtered`, prepend (or append) projected entries from `store.factoryMethods()`:

  ```java
  public Map<String, Object> execute(Map<String, Object> args) {
      var scope = strOrNull(args.get("scope"));
      var iface = strOrNull(args.get("interface"));

      var components = new java.util.ArrayList<Map<String, Object>>();
      for (var c : store.components()) {
          if (scope != null && !scope.equals(c.get("scope"))) continue;
          if (iface != null && !interfacesContain(c, iface)) continue;
          components.add(withKind(c, "COMPONENT"));
      }
      for (var f : store.factoryMethods()) {
          if (scope != null && !scope.equals(f.get("scope"))) continue;
          if (iface != null && !iface.equals(f.get("returnType"))) continue;
          components.add(projectedFromFactory(f));
      }

      var out = new LinkedHashMap<String, Object>();
      out.put("components", components);
      return out;
  }

  private static Map<String, Object> withKind(Map<String, Object> c, String kind) {
      var out = new LinkedHashMap<String, Object>(c);
      out.putIfAbsent("kind", kind);
      return out;
  }

  private static Map<String, Object> projectedFromFactory(Map<String, Object> f) {
      var out = new LinkedHashMap<String, Object>();
      out.put("kind", "PRODUCED");
      out.put("qualifiedName", f.get("returnType"));
      out.put("scope", f.get("scope"));
      out.put("qualifier", f.get("qualifier"));
      out.put("profiles", f.getOrDefault("profiles", java.util.List.of()));
      out.put("interfaces", java.util.List.of()); // not known from the producer signature
      out.put("isTestComponent", false);
      out.put("requiresProxy", f.getOrDefault("requiresProxy", false));
      out.put("constructorDependencies", f.getOrDefault("constructorDependencies", java.util.List.of()));
      var producedBy = new LinkedHashMap<String, Object>();
      producedBy.put("componentFqn", f.get("declaringClass"));
      producedBy.put("methodName", f.get("methodName"));
      producedBy.put("isStatic", f.getOrDefault("static", false));
      out.put("producedBy", producedBy);
      return out;
  }
  ```

- [ ] **Step 2.5: Run test to verify it passes**

  Expected: PASS. Run the whole `ListComponentsToolTest` class to confirm no regression on existing tests.

- [ ] **Step 2.6: Write failing test — `explain_wiring` walks producer edges**

  Append to `ExplainWiringToolTest.java`:

  ```java
  @Test
  void walksProducerEdge(@TempDir Path root) throws Exception {
      var store = storeWith(root, """
              {"schemaVersion":1,"module":"m",
               "components":[
                 {"qualifiedName":"example.Producers","scope":"SINGLETON","interfaces":[],
                  "constructorDependencies":[]},
                 {"qualifiedName":"example.Repo","scope":"SINGLETON","interfaces":[],
                  "constructorDependencies":[
                    {"type":"javax.sql.DataSource","qualifier":"primary","kind":"DIRECT","pickedType":null}]}
               ],
               "factoryMethods":[
                 {"declaringClass":"example.Producers","methodName":"db",
                  "returnType":"javax.sql.DataSource","scope":"SINGLETON","qualifier":"primary",
                  "profiles":[],"static":false,"autoCloseable":true,"requiresProxy":false,
                  "constructorDependencies":[]}
               ],
               "eventHandlers":[],"eventTriggers":[],"configurations":[]}
              """);
      var tool = new ExplainWiringTool(store);

      @SuppressWarnings("unchecked")
      var tree = (List<Map<String, Object>>)
              tool.execute(Map.of("componentFqn", "example.Repo")).get("tree");

      // Expect three nodes: Repo (depth 0), DataSource produced by Producers (depth 1),
      // Producers component itself (depth 2 via producer edge).
      assertThat(tree).hasSize(3);
      assertThat(tree.get(1).get("kind")).isEqualTo("PRODUCED");
      assertThat(tree.get(2).get("kind")).isEqualTo("COMPONENT");
      @SuppressWarnings("unchecked")
      var producedBy = (Map<String, Object>) ((Map<String, Object>) tree.get(1).get("component")).get("producedBy");
      assertThat(producedBy.get("componentFqn")).isEqualTo("example.Producers");
  }
  ```

- [ ] **Step 2.7: Run test to verify it fails**

  Expected: FAIL — current code returns only `Repo` then a CONFIG miss / null for DataSource.

- [ ] **Step 2.8: Extend `ExplainWiringTool` to recognise producer edges**

  In `tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java`, after the existing `findComponent` and `findConfiguration` checks inside the BFS loop, add a `findFactory` branch:

  ```java
  var factory = findFactory(n.fqn);
  if (factory != null) {
      var isCycle = !visited.add(n.fqn);
      var entry = new LinkedHashMap<String, Object>();
      entry.put("depth", n.depth);
      entry.put("kind", "PRODUCED");
      entry.put("component", projectedFromFactory(factory));
      entry.put("via", n.via);
      entry.put("cycle", isCycle);
      entry.put("proxied", Boolean.TRUE.equals(factory.get("requiresProxy")));
      tree.add(entry);
      if (isCycle) continue;
      // Descend into producer component + producer method dependencies.
      queue.add(new Node((String) factory.get("declaringClass"), n.depth + 1, null));
      @SuppressWarnings("unchecked")
      var deps = (List<Map<String, Object>>) factory.getOrDefault("constructorDependencies", List.of());
      for (var dep : deps) {
          var depType = (String) dep.get("type");
          if (depType != null) queue.add(new Node(depType, n.depth + 1, dep));
      }
      continue;
  }
  ```

  Add helpers (`findFactory`, `projectedFromFactory`) mirroring the projection in Task 2.4. `findFactory` looks up by `returnType` (with optional `qualifier` match if you want strict qualifier resolution — keep it loose by `returnType` for the first pass).

- [ ] **Step 2.9: Run test to verify it passes**

  Expected: PASS. Run the whole `ExplainWiringToolTest` to confirm no regression.

- [ ] **Step 2.10: Update example 13 with a `@Produces` bean**

  Add `tiko-examples/13_mcp_introspection/src/main/java/example/Producers.java`:

  ```java
  package example;

  import io.tiko.annotations.Component;
  import io.tiko.annotations.Produces;
  import io.tiko.annotations.Scope;

  @Component(scope = Scope.SINGLETON)
  public final class Producers {

      @Produces(scope = Scope.SINGLETON, name = "primary")
      public DbConfig.HikariShim primaryShim(DbConfig cfg) {
          return new DbConfig.HikariShim(cfg.url(), cfg.username());
      }
  }
  ```

  In `tiko-examples/13_mcp_introspection/src/main/java/example/DbConfig.java`, add a nested record `HikariShim(String url, String username)` next to the existing `@Configuration` body if it doesn't already exist.

  Make a component depend on `HikariShim` (e.g. extend `OrderRepository` to take `HikariShim` via `@Named("primary")`) — the goal is for `explain_wiring example.OrderRepository` to walk through the producer edge in manual QA.

  Run `W:\tools\apache-maven\bin\mvn -pl tiko-examples/13_mcp_introspection compile` and confirm it compiles.

- [ ] **Step 2.11: Build, test, format**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp,tiko-examples/13_mcp_introspection clean test
  ```

  Expected: BUILD SUCCESS.

- [ ] **Step 2.12: Commit and open PR**

  ```bash
  git add tiko-mcp/ tiko-examples/13_mcp_introspection/
  git commit -m "feat(mcp): surface @Produces factories in list_components and explain_wiring (#143)"
  git push -u origin mcp-surface-produces
  "C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(mcp): surface @Produces in list_components/explain_wiring (#143)" --body "Closes #143. \`list_components\` now emits PRODUCED entries projected from \`factoryMethods[]\` with \`producedBy: {componentFqn, methodName, isStatic}\`. \`explain_wiring\` walks producer edges so an \`@Inject\` of a produced type shows the producer and recurses into its deps."
  ```

---

## Task 3: #142 — `list_wiring_errors` tool

**GitHub:** #142

**Branch:** `mcp-wiring-errors-tool` off `worktree-phase3-followups`

**Approach:** Add a new processor output `META-INF/tiko/wiring-errors.json` emitted whenever validation diagnostics are collected. `TopologyStore` loads it if present. New MCP tool returns the parsed list. On a clean build, the file does not exist (or is empty) — tool returns `{"errors": []}`.

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/model/WiringError.java`
- Create: `tiko-processor/src/main/java/io/tiko/processor/topology/WiringErrorsWriter.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` (or wherever diagnostics are routed to the messager) — also persist them
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListWiringErrorsTool.java`
- Create: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListWiringErrorsToolTest.java`
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java` (load `wiring-errors.json`)
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java`, `McpStdioBridge.java`
- Modify: `tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java`
- Modify: `docs/topology-schema.md` (add a `wiring-errors.json` section)

### Steps

- [ ] **Step 3.1: Branch**

  ```bash
  git checkout worktree-phase3-followups
  git pull --ff-only
  git checkout -b mcp-wiring-errors-tool
  ```

- [ ] **Step 3.2: Locate where the processor emits diagnostics**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-processor dependency:tree -q
  ```

  Then grep:

  ```bash
  ```

  Use Grep tool for `Messager` calls in `tiko-processor/src/main/java/`. List every diagnostic-emission site to confirm a single chokepoint exists (e.g. a `Diagnostics` helper). If multiple sites emit directly, refactor in Step 3.5 to funnel through a collector.

- [ ] **Step 3.3: Define `WiringError` record**

  Create `tiko-processor/src/main/java/io/tiko/processor/model/WiringError.java`:

  ```java
  package io.tiko.processor.model;

  /**
   * Compile-time diagnostic captured for downstream tooling. Persisted to
   * META-INF/tiko/wiring-errors.json alongside topology.json.
   *
   * @param kind         One of MISSING_DEPENDENCY, CIRCULAR_DEPENDENCY, SCOPE_VIOLATION,
   *                     AMBIGUOUS_QUALIFIER, BAD_PRODUCES, OTHER.
   * @param sourceFile   Best-effort file path relative to project root, null when not derivable.
   * @param line         1-based line number, 0 when not derivable.
   * @param componentFqn FQN of the component that owns the diagnostic, may be null.
   * @param message      Human-readable message (matches the Messager text).
   * @param suggestedFix Optional one-line remediation hint.
   */
  public record WiringError(
          String kind,
          String sourceFile,
          int line,
          String componentFqn,
          String message,
          String suggestedFix) {}
  ```

- [ ] **Step 3.4: Implement `WiringErrorsWriter`**

  Create `tiko-processor/src/main/java/io/tiko/processor/topology/WiringErrorsWriter.java`:

  ```java
  package io.tiko.processor.topology;

  import io.tiko.processor.model.WiringError;
  import java.io.IOException;
  import java.io.StringWriter;
  import java.io.Writer;
  import java.util.List;
  import javax.annotation.processing.Filer;
  import javax.tools.FileObject;
  import javax.tools.StandardLocation;

  /** Emits META-INF/tiko/wiring-errors.json. Empty list still writes a valid file. */
  public final class WiringErrorsWriter {

      private static final String PATH = "META-INF/tiko/wiring-errors.json";
      private final List<WiringError> errors;

      public WiringErrorsWriter(List<WiringError> errors) {
          this.errors = errors;
      }

      public void write(Filer filer) throws IOException {
          FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
          try (Writer w = f.openWriter()) {
              renderTo(w);
          }
      }

      public String render() {
          var sw = new StringWriter();
          renderTo(sw);
          return sw.toString();
      }

      private void renderTo(Writer w) {
          try (var jw = new JsonWriter(w, true)) {
              jw.object();
              jw.field("errors").array();
              for (WiringError e : errors) {
                  jw.object();
                  jw.field("kind").value(e.kind());
                  jw.field("sourceFile").value(e.sourceFile());
                  jw.field("line").value(e.line());
                  jw.field("componentFqn").value(e.componentFqn());
                  jw.field("message").value(e.message());
                  jw.field("suggestedFix").value(e.suggestedFix());
                  jw.endObject();
              }
              jw.endArray();
              jw.endObject();
          }
      }
  }
  ```

- [ ] **Step 3.5: Funnel diagnostics through a collector and emit on round end**

  In `tiko-processor/src/main/java/io/tiko/processor/util/ProcessorContext.java` (or whichever class already aggregates per-round state):
  - Add `private final List<WiringError> wiringErrors = new ArrayList<>();`
  - Add `public void addWiringError(WiringError e) { wiringErrors.add(e); }`
  - Add `public List<WiringError> getWiringErrors() { return List.copyOf(wiringErrors); }`

  At every site that currently calls `Messager.printMessage(Diagnostic.Kind.ERROR, ...)`, **also** call `context.addWiringError(...)`. Use the existing message text verbatim for `message`; derive `line` and `sourceFile` from the offending `Element`.

  In the processor's `processOver()` / final round (where `TopologyWriter.write(filer)` is called), add:

  ```java
  new WiringErrorsWriter(context.getWiringErrors()).write(filer);
  ```

  Emit even on empty list — see acceptance criteria.

- [ ] **Step 3.6: Write failing test — `ListWiringErrorsTool` returns parsed errors**

  Create `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListWiringErrorsToolTest.java`:

  ```java
  package io.tiko.mcp.tools;

  import static org.assertj.core.api.Assertions.assertThat;

  import io.tiko.mcp.TopologyStore;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;
  import java.util.Map;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  class ListWiringErrorsToolTest {

      @Test
      void returnsEmptyListWhenFileMissing(@TempDir Path root) throws Exception {
          var topo = root.resolve("m/target/classes/META-INF/tiko/topology.json");
          Files.createDirectories(topo.getParent());
          Files.writeString(topo, """
                  {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],
                   "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """, StandardCharsets.UTF_8);
          var tool = new ListWiringErrorsTool(TopologyStore.loadFrom(root));

          @SuppressWarnings("unchecked")
          List<Map<String, Object>> errors =
                  (List<Map<String, Object>>) tool.execute(Map.of()).get("errors");
          assertThat(errors).isEmpty();
      }

      @Test
      void returnsErrorsFromFile(@TempDir Path root) throws Exception {
          var dir = root.resolve("m/target/classes/META-INF/tiko/");
          Files.createDirectories(dir);
          Files.writeString(dir.resolve("topology.json"), """
                  {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],
                   "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """, StandardCharsets.UTF_8);
          Files.writeString(dir.resolve("wiring-errors.json"), """
                  {"errors":[
                    {"kind":"MISSING_DEPENDENCY","sourceFile":"src/main/java/X.java","line":17,
                     "componentFqn":"example.X","message":"No component provides Y",
                     "suggestedFix":"Add @Component to Y"}
                  ]}
                  """, StandardCharsets.UTF_8);

          var tool = new ListWiringErrorsTool(TopologyStore.loadFrom(root));
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> errors =
                  (List<Map<String, Object>>) tool.execute(Map.of()).get("errors");
          assertThat(errors).hasSize(1);
          assertThat(errors.get(0).get("kind")).isEqualTo("MISSING_DEPENDENCY");
      }
  }
  ```

- [ ] **Step 3.7: Extend `TopologyStore` to load wiring-errors.json**

  In `TopologyStore`:
  - Add `private final List<Map<String, Object>> wiringErrors;` field + constructor param.
  - In `loadFrom`, after the `topology.json` loop, add a `wiring-errors.json` loop:

  ```java
  var wiringErrors = new ArrayList<Map<String, Object>>();
  for (var path : findFiles(projectRoot, "wiring-errors.json")) {
      var doc = readJsonObject(path);
      appendIfArray(doc, "errors", wiringErrors);
  }
  ```

  - Pass `wiringErrors` through the constructor.
  - Add `public List<Map<String, Object>> wiringErrors() { return wiringErrors; }`
  - Update `reload()` (from Task 1) to also clear+repopulate `wiringErrors`.

- [ ] **Step 3.8: Implement `ListWiringErrorsTool`**

  Create `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListWiringErrorsTool.java`:

  ```java
  package io.tiko.mcp.tools;

  import io.tiko.mcp.TopologyStore;
  import java.util.LinkedHashMap;
  import java.util.Map;

  /**
   * MCP tool: returns processor diagnostics (missing deps, circular deps, scope
   * violations, ambiguous qualifiers, bad {@code @Produces} signatures) persisted
   * to META-INF/tiko/wiring-errors.json alongside topology.json.
   *
   * <p>Clean build → {@code {"errors": []}}.
   */
  public final class ListWiringErrorsTool {

      public static final String NAME = "list_wiring_errors";

      private final TopologyStore store;

      public ListWiringErrorsTool(TopologyStore store) {
          this.store = store;
      }

      public Map<String, Object> execute(Map<String, Object> args) {
          var out = new LinkedHashMap<String, Object>();
          out.put("errors", store.wiringErrors());
          return out;
      }
  }
  ```

- [ ] **Step 3.9: Run unit tests to verify**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=ListWiringErrorsToolTest
  ```

  Expected: 2 PASS.

- [ ] **Step 3.10: Add processor integration test for emission**

  In `tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterTest.java` (or a new `WiringErrorsWriterTest.java`), add a test using the existing `compile-testing` setup that compiles a sample with a missing dep and asserts `wiring-errors.json` contents — the exact existing test scaffolds in `tiko-processor/src/test/` are the template; mirror their structure.

- [ ] **Step 3.11: Wire `ListWiringErrorsTool` into the server and bridge**

  Same pattern as Task 1.9–1.10:
  - `TikoMcpServer`: `var listWiringErrors = new ListWiringErrorsTool(store);` + pass into bridge.
  - `McpStdioBridge`: new field + constructor param, new `listWiringErrorsSchema = """{"type":"object","properties":{}}"""`, new `spec(...)` entry.

- [ ] **Step 3.12: Document `wiring-errors.json` in `docs/topology-schema.md`**

  Add a new section after `## configurations[]`:

  ````markdown
  ## Sibling artifact: `wiring-errors.json`

  Emitted alongside `topology.json` whenever validation collects any diagnostics.
  An empty `{"errors": []}` ships on a clean build so consumers can rely on the file
  always being present.

  | Field          | Type           | Notes |
  | -------------- | -------------- | ----- |
  | `kind`         | string enum    | `MISSING_DEPENDENCY` / `CIRCULAR_DEPENDENCY` / `SCOPE_VIOLATION` / `AMBIGUOUS_QUALIFIER` / `BAD_PRODUCES` / `OTHER` |
  | `sourceFile`   | string \| null | Project-relative path, best-effort |
  | `line`         | integer        | 1-based, 0 when not derivable |
  | `componentFqn` | string \| null | Owning component when known |
  | `message`      | string         | Same text the Messager prints |
  | `suggestedFix` | string \| null | One-line hint when available |
  ````

- [ ] **Step 3.13: Extend `TikoMcpServerSubprocessIT`**

  Add `"list_wiring_errors"` to `EXPECTED_TOOLS` and to the assertion chain.

- [ ] **Step 3.14: Build, test, format, commit, PR**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply
  W:\tools\apache-maven\bin\mvn clean install -DskipTests=false
  git add tiko-processor/ tiko-mcp/ docs/topology-schema.md
  git commit -m "feat(processor,mcp): emit wiring-errors.json + list_wiring_errors tool (#142)"
  git push -u origin mcp-wiring-errors-tool
  "C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(processor,mcp): list_wiring_errors (#142)" --body "Closes #142. Processor persists diagnostics to META-INF/tiko/wiring-errors.json alongside topology.json. New MCP tool \`list_wiring_errors\` returns the parsed list. Clean build → \`{\"errors\": []}\`. Documents the new artifact in docs/topology-schema.md."
  ```

---

## Task 4: #141 — `find_dependents` tool

**GitHub:** #141

**Branch:** `mcp-find-dependents-tool` off `worktree-phase3-followups`

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/FindDependentsTool.java`
- Create: `tiko-mcp/src/test/java/io/tiko/mcp/tools/FindDependentsToolTest.java`
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java` (lazy reverse-index cache; invalidate on `reload()`)
- Modify: `TikoMcpServer.java`, `McpStdioBridge.java`, `TikoMcpServerSubprocessIT.java`

### Steps

- [ ] **Step 4.1: Branch**

  ```bash
  git checkout worktree-phase3-followups
  git pull --ff-only
  git checkout -b mcp-find-dependents-tool
  ```

- [ ] **Step 4.2: Write failing test**

  Create `tiko-mcp/src/test/java/io/tiko/mcp/tools/FindDependentsToolTest.java`:

  ```java
  package io.tiko.mcp.tools;

  import static org.assertj.core.api.Assertions.assertThat;

  import io.tiko.mcp.TopologyStore;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;
  import java.util.Map;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  class FindDependentsToolTest {

      @Test
      void directDependents(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[
                     {"qualifiedName":"example.Orders","scope":"SINGLETON","interfaces":[],
                      "constructorDependencies":[]},
                     {"qualifiedName":"example.OrderService","scope":"SINGLETON","interfaces":[],
                      "constructorDependencies":[{"type":"example.Orders","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                   ],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """);
          var tool = new FindDependentsTool(store);

          @SuppressWarnings("unchecked")
          List<String> dependents = (List<String>)
                  tool.execute(Map.of("componentFqn", "example.Orders")).get("dependents");
          assertThat(dependents).containsExactly("example.OrderService");
      }

      @Test
      void transitiveDependents(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[
                     {"qualifiedName":"example.Orders","scope":"SINGLETON","interfaces":[],
                      "constructorDependencies":[]},
                     {"qualifiedName":"example.OrderService","scope":"SINGLETON","interfaces":[],
                      "constructorDependencies":[{"type":"example.Orders","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                     {"qualifiedName":"example.OrderController","scope":"SINGLETON","interfaces":[],
                      "constructorDependencies":[{"type":"example.OrderService","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                   ],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """);
          var tool = new FindDependentsTool(store);

          @SuppressWarnings("unchecked")
          List<String> dependents = (List<String>) tool.execute(
                  Map.of("componentFqn", "example.Orders", "transitive", true)).get("dependents");
          assertThat(dependents).containsExactlyInAnyOrder("example.OrderService", "example.OrderController");
      }

      @Test
      void unknownFqnThrowsWithDidYouMean(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[
                     {"qualifiedName":"example.Orders","scope":"SINGLETON","interfaces":[],
                      "constructorDependencies":[]}
                   ],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """);
          var tool = new FindDependentsTool(store);
          org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                  () -> tool.execute(Map.of("componentFqn", "example.NotARealClass")));
      }

      private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
          var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
          Files.createDirectories(f.getParent());
          Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
          return TopologyStore.loadFrom(root);
      }
  }
  ```

- [ ] **Step 4.3: Run test to verify it fails**

  Expected: FAIL — `FindDependentsTool` does not exist.

- [ ] **Step 4.4: Implement `FindDependentsTool`**

  Create `tiko-mcp/src/main/java/io/tiko/mcp/tools/FindDependentsTool.java`:

  ```java
  package io.tiko.mcp.tools;

  import io.tiko.mcp.TopologyStore;
  import java.util.ArrayDeque;
  import java.util.ArrayList;
  import java.util.HashSet;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  /**
   * MCP tool: reverse-index lookup. Given {@code componentFqn}, returns the list of
   * components whose {@code constructorDependencies} reference it. With {@code
   * transitive: true}, walks the reverse graph (visited set caps cycles).
   *
   * <p>Configurations are valid lookup targets — they appear as deps in
   * {@code constructorDependencies}, same as components.
   */
  public final class FindDependentsTool {

      public static final String NAME = "find_dependents";

      private final TopologyStore store;

      public FindDependentsTool(TopologyStore store) {
          this.store = store;
      }

      public Map<String, Object> execute(Map<String, Object> args) {
          var target = required(args, "componentFqn");
          boolean transitive = Boolean.TRUE.equals(args.get("transitive"));

          if (!isKnown(target)) {
              var matches = candidateMatches(target);
              var msg = "Unknown component '" + target + "'.";
              if (!matches.isEmpty()) msg += " Did you mean one of: " + matches + "?";
              throw new IllegalArgumentException(msg);
          }

          var direct = directDependents(target);
          var dependents = transitive ? walkReverse(direct) : direct;

          var out = new LinkedHashMap<String, Object>();
          out.put("dependents", new ArrayList<>(dependents));
          return out;
      }

      private boolean isKnown(String fqn) {
          for (var c : store.components()) if (fqn.equals(c.get("qualifiedName"))) return true;
          for (var cfg : store.configurations()) if (fqn.equals(cfg.get("qualifiedName"))) return true;
          return false;
      }

      private List<String> candidateMatches(String fqn) {
          var simple = simpleName(fqn);
          var result = new ArrayList<String>();
          for (var c : store.components()) {
              var n = (String) c.get("qualifiedName");
              if (n != null && n.contains(simple)) result.add(n);
          }
          return result;
      }

      private List<String> directDependents(String target) {
          var result = new ArrayList<String>();
          for (var c : store.components()) {
              @SuppressWarnings("unchecked")
              var deps = (List<Map<String, Object>>) c.getOrDefault("constructorDependencies", List.of());
              for (var d : deps) {
                  if (target.equals(d.get("type"))) {
                      result.add((String) c.get("qualifiedName"));
                      break;
                  }
              }
          }
          return result;
      }

      private List<String> walkReverse(List<String> seeds) {
          var visited = new HashSet<>(seeds);
          var queue = new ArrayDeque<>(seeds);
          var result = new ArrayList<>(seeds);
          while (!queue.isEmpty()) {
              var current = queue.poll();
              for (var d : directDependents(current)) {
                  if (visited.add(d)) {
                      result.add(d);
                      queue.add(d);
                  }
              }
          }
          return result;
      }

      private static String simpleName(String fqn) {
          var dot = fqn.lastIndexOf('.');
          return dot < 0 ? fqn : fqn.substring(dot + 1);
      }

      private static String required(Map<String, Object> args, String key) {
          var v = args.get(key);
          if (v == null || v.toString().isEmpty()) {
              throw new IllegalArgumentException("Missing required argument: " + key);
          }
          return v.toString();
      }
  }
  ```

- [ ] **Step 4.5: Run all `FindDependentsToolTest` tests to verify they pass**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=FindDependentsToolTest
  ```

  Expected: 3 PASS.

- [ ] **Step 4.6: Wire into server and bridge**

  Same pattern as Task 1.9–1.10. Schema:

  ```java
  var findDependentsSchema = """
          {"type":"object","properties":{
             "componentFqn":{"type":"string"},
             "transitive":{"type":"boolean","default":false}},
           "required":["componentFqn"]}""";
  ```

- [ ] **Step 4.7: Extend IT, format, build, commit, PR**

  Add `"find_dependents"` to `EXPECTED_TOOLS`. Then:

  ```bash
  W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp clean test
  git add tiko-mcp/
  git commit -m "feat(mcp): add find_dependents reverse-dependency tool (#141)"
  git push -u origin mcp-find-dependents-tool
  "C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(mcp): find_dependents (#141)" --body "Closes #141. New MCP tool \`find_dependents {componentFqn, transitive?}\` returns the reverse index of who injects/depends on the given component. Default \`transitive: false\` returns direct dependents only; \`true\` walks the reverse graph with cycle protection. Unknown FQN → did-you-mean error matching explain_wiring style."
  ```

---

## Task 5: #140 — `trace_event_flow` tool

**GitHub:** #140

**Branch:** `mcp-trace-event-flow-tool` off `worktree-phase3-followups`

**Background:** `eventHandlers[]` and `eventTriggers[]` already carry the needed metadata (see `TopologyWriter.writeEventTriggers` — `handlerClass`, `handlerMethod`, `eventName`, `eventType`, `async`, `spread`, `guards`). No processor work needed. The tool walks: start event → handlers of that event → triggers on those handlers → next events → repeat.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/TraceEventFlowTool.java`
- Create: `tiko-mcp/src/test/java/io/tiko/mcp/tools/TraceEventFlowToolTest.java`
- Modify: `TikoMcpServer.java`, `McpStdioBridge.java`, `TikoMcpServerSubprocessIT.java`
- (Optional, manual QA) Add an `async = true` trigger to `tiko-examples/13_mcp_introspection`

### Steps

- [ ] **Step 5.1: Branch**

  ```bash
  git checkout worktree-phase3-followups
  git pull --ff-only
  git checkout -b mcp-trace-event-flow-tool
  ```

- [ ] **Step 5.2: Write failing test**

  Create `tiko-mcp/src/test/java/io/tiko/mcp/tools/TraceEventFlowToolTest.java`:

  ```java
  package io.tiko.mcp.tools;

  import static org.assertj.core.api.Assertions.assertThat;

  import io.tiko.mcp.TopologyStore;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;
  import java.util.Map;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  class TraceEventFlowToolTest {

      @Test
      void tracesLinearChain(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[],"factoryMethods":[],"configurations":[],
                   "eventHandlers":[
                     {"declaringClass":"example.OrderService","methodName":"validate",
                      "eventType":"example.events.OrderPlaced","async":false,"hasEventWrapper":false}
                   ],
                   "eventTriggers":[
                     {"handlerClass":"example.OrderService","handlerMethod":"validate",
                      "eventName":"OrderValidated","eventType":"example.events.OrderValidated",
                      "async":false,"spread":false,"guards":[]}
                   ]}
                  """);
          var tool = new TraceEventFlowTool(store);

          @SuppressWarnings("unchecked")
          List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                  tool.execute(Map.of("eventType", "example.events.OrderPlaced")).get("nodes");
          assertThat(nodes).hasSize(2);
          assertThat(nodes.get(0).get("event")).isEqualTo("example.events.OrderPlaced");
          assertThat(nodes.get(1).get("event")).isEqualTo("example.events.OrderValidated");
          assertThat(nodes.get(1).get("terminal")).isEqualTo(Boolean.TRUE);
      }

      @Test
      void cycleDetected(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[],"factoryMethods":[],"configurations":[],
                   "eventHandlers":[
                     {"declaringClass":"x.A","methodName":"h1","eventType":"x.E1","async":false,"hasEventWrapper":false},
                     {"declaringClass":"x.B","methodName":"h2","eventType":"x.E2","async":false,"hasEventWrapper":false}
                   ],
                   "eventTriggers":[
                     {"handlerClass":"x.A","handlerMethod":"h1","eventName":"E2","eventType":"x.E2","async":false,"spread":false,"guards":[]},
                     {"handlerClass":"x.B","handlerMethod":"h2","eventName":"E1","eventType":"x.E1","async":false,"spread":false,"guards":[]}
                   ]}
                  """);
          var tool = new TraceEventFlowTool(store);

          @SuppressWarnings("unchecked")
          List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                  tool.execute(Map.of("eventType", "x.E1")).get("nodes");
          // Two distinct event nodes, both reachable; second visit marked cycle.
          assertThat(nodes).hasSize(2);
          assertThat(nodes).anySatisfy(n -> assertThat(n.get("cycle")).isEqualTo(Boolean.TRUE));
      }

      @Test
      void unknownEventThrows(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[],"factoryMethods":[],"configurations":[],
                   "eventHandlers":[],"eventTriggers":[]}
                  """);
          var tool = new TraceEventFlowTool(store);
          org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                  () -> tool.execute(Map.of("eventType", "nope.NotAnEvent")));
      }

      private TopologyStore storeWith(Path root, String json) throws Exception {
          var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
          Files.createDirectories(f.getParent());
          Files.writeString(f, json, StandardCharsets.UTF_8);
          return TopologyStore.loadFrom(root);
      }
  }
  ```

- [ ] **Step 5.3: Run test to verify it fails**

  Expected: FAIL — `TraceEventFlowTool` does not exist.

- [ ] **Step 5.4: Implement `TraceEventFlowTool`**

  Create `tiko-mcp/src/main/java/io/tiko/mcp/tools/TraceEventFlowTool.java`:

  ```java
  package io.tiko.mcp.tools;

  import io.tiko.mcp.TopologyStore;
  import java.util.ArrayDeque;
  import java.util.ArrayList;
  import java.util.HashSet;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  /**
   * MCP tool: walks {@code @EventTrigger} chains and returns the event-flow DAG starting from the
   * given event type. Each node holds the event FQN plus the outbound edges (handler → trigger).
   * Terminal events (no handlers or no triggers) carry {@code terminal: true}; revisits carry
   * {@code cycle: true}.
   *
   * <p>Purely static — derived from the processor's {@code eventHandlers[]} and
   * {@code eventTriggers[]} sections. Programmatic {@code EventBus.publish(...)} calls are not seen.
   */
  public final class TraceEventFlowTool {

      public static final String NAME = "trace_event_flow";

      private static final long DEFAULT_MAX_DEPTH = 20L;

      private final TopologyStore store;

      public TraceEventFlowTool(TopologyStore store) {
          this.store = store;
      }

      public Map<String, Object> execute(Map<String, Object> args) {
          var eventType = required(args, "eventType");
          long maxDepth = args.get("maxDepth") instanceof Long l ? l : DEFAULT_MAX_DEPTH;

          if (handlersFor(eventType).isEmpty() && !isReachable(eventType)) {
              throw new IllegalArgumentException("Unknown event '" + eventType + "'.");
          }

          var nodes = new ArrayList<Map<String, Object>>();
          var visited = new HashSet<String>();
          var queue = new ArrayDeque<Frame>();
          queue.add(new Frame(eventType, 0));

          while (!queue.isEmpty()) {
              var f = queue.poll();
              if (f.depth > maxDepth) continue;

              boolean isCycle = !visited.add(f.event);
              var node = new LinkedHashMap<String, Object>();
              node.put("event", f.event);
              node.put("depth", (long) f.depth);
              node.put("cycle", isCycle);

              var edges = new ArrayList<Map<String, Object>>();
              if (!isCycle) {
                  for (var handler : handlersFor(f.event)) {
                      var handlerClass = (String) handler.get("declaringClass");
                      var handlerMethod = (String) handler.get("methodName");
                      for (var trig : triggersOn(handlerClass, handlerMethod)) {
                          var next = (String) trig.get("eventType");
                          var edge = new LinkedHashMap<String, Object>();
                          edge.put("via", handlerClass + "#" + handlerMethod);
                          edge.put("eventName", trig.get("eventName"));
                          edge.put("async", trig.getOrDefault("async", false));
                          edge.put("spread", trig.getOrDefault("spread", false));
                          edge.put("guards", trig.getOrDefault("guards", List.of()));
                          edge.put("nextEvent", next);
                          edges.add(edge);
                          if (next != null) queue.add(new Frame(next, f.depth + 1));
                      }
                  }
              }
              node.put("edges", edges);
              node.put("terminal", edges.isEmpty() && !isCycle);
              nodes.add(node);
          }

          var out = new LinkedHashMap<String, Object>();
          out.put("root", eventType);
          out.put("nodes", nodes);
          return out;
      }

      private List<Map<String, Object>> handlersFor(String eventType) {
          var result = new ArrayList<Map<String, Object>>();
          for (var h : store.eventHandlers()) {
              if (eventType.equals(h.get("eventType"))) result.add(h);
          }
          return result;
      }

      private List<Map<String, Object>> triggersOn(String handlerClass, String handlerMethod) {
          var result = new ArrayList<Map<String, Object>>();
          for (var t : store.eventTriggers()) {
              if (handlerClass.equals(t.get("handlerClass"))
                      && handlerMethod.equals(t.get("handlerMethod"))) {
                  result.add(t);
              }
          }
          return result;
      }

      private boolean isReachable(String eventType) {
          for (var t : store.eventTriggers()) {
              if (eventType.equals(t.get("eventType"))) return true;
          }
          return false;
      }

      private static String required(Map<String, Object> args, String key) {
          var v = args.get(key);
          if (v == null || v.toString().isEmpty()) {
              throw new IllegalArgumentException("Missing required argument: " + key);
          }
          return v.toString();
      }

      private record Frame(String event, int depth) {}
  }
  ```

- [ ] **Step 5.5: Run tests to verify all pass**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=TraceEventFlowToolTest
  ```

  Expected: 3 PASS.

- [ ] **Step 5.6: Wire into server and bridge**

  Schema:

  ```java
  var traceEventFlowSchema = """
          {"type":"object","properties":{
             "eventType":{"type":"string"},
             "maxDepth":{"type":"integer","default":20}},
           "required":["eventType"]}""";
  ```

- [ ] **Step 5.7: (Optional) Add async trigger to example 13 for manual QA**

  In `tiko-examples/13_mcp_introspection/src/main/java/example/OrderService.java`, add an `@EventTrigger(async = true, eventName = "OrderArchived")` on a second handler so the DAG demonstrates `async: true` in real output. Not required for the test suite — only valuable for `mvn package && java -jar tiko-mcp/target/tiko-mcp-*.jar tiko-examples/13_mcp_introspection` manual QA.

- [ ] **Step 5.8: Extend IT, format, build, commit, PR**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp clean test
  git add tiko-mcp/ tiko-examples/13_mcp_introspection/
  git commit -m "feat(mcp): add trace_event_flow tool for @EventTrigger DAG (#140)"
  git push -u origin mcp-trace-event-flow-tool
  "C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(mcp): trace_event_flow (#140)" --body "Closes #140. New MCP tool \`trace_event_flow {eventType, maxDepth?}\` walks @EventTrigger chains and returns the event-flow DAG. Nodes carry depth/cycle/terminal; edges carry via/eventName/async/spread/guards/nextEvent. Cycles bounded by visited set. Unknown event → clean error."
  ```

---

## Task 6: #144 — profile-aware filtering + `list_profile_conflicts`

**GitHub:** #144

**Branch:** `mcp-profile-aware-queries` off `worktree-phase3-followups`

**Files:**
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java` (add `profile` filter)
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java` (add `profile` param, scope graph)
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListProfileConflictsTool.java`
- Create: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListProfileConflictsToolTest.java`
- Modify: `ListComponentsToolTest.java`, `ExplainWiringToolTest.java`
- Modify: `tiko-examples/13_mcp_introspection/` — add a `dev` and `prod` impl of the same interface for IT/QA
- Modify: `TikoMcpServer.java`, `McpStdioBridge.java`, `TikoMcpServerSubprocessIT.java`

### Steps

- [ ] **Step 6.1: Branch**

  ```bash
  git checkout worktree-phase3-followups
  git pull --ff-only
  git checkout -b mcp-profile-aware-queries
  ```

- [ ] **Step 6.2: Write failing test — `list_components` profile filter**

  Append to `ListComponentsToolTest.java`:

  ```java
  @Test
  void filterByProfile(@TempDir Path root) throws Exception {
      var store = storeWith(root, """
              {"schemaVersion":1,"module":"m",
               "components":[
                 {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["dev"]},
                 {"qualifiedName":"x.ProdImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["prod"]},
                 {"qualifiedName":"x.Always","scope":"SINGLETON","interfaces":[],"profiles":[]}
               ],
               "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
              """);
      var tool = new ListComponentsTool(store);

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> dev = (List<Map<String, Object>>)
              tool.execute(Map.of("profile", "dev")).get("components");
      assertThat(dev).extracting(c -> c.get("qualifiedName"))
              .containsExactlyInAnyOrder("x.DevImpl", "x.Always");
  }
  ```

  Convention: empty `profiles[]` means "active in all profiles" (matches the runtime container's behaviour).

- [ ] **Step 6.3: Run test to verify it fails**

  Expected: FAIL — profile arg is ignored.

- [ ] **Step 6.4: Implement profile filter in `ListComponentsTool`**

  Add to `execute(...)`:

  ```java
  var profile = strOrNull(args.get("profile"));
  // ...inside the loop:
  if (profile != null && !profileMatches(c, profile)) continue;
  ```

  Helper:

  ```java
  @SuppressWarnings("unchecked")
  private static boolean profileMatches(Map<String, Object> entry, String profile) {
      var v = entry.get("profiles");
      if (!(v instanceof List<?> list) || list.isEmpty()) return true; // active under all profiles
      return list.stream().anyMatch(profile::equals);
  }
  ```

  Apply the same check inside the `factoryMethods` loop projection.

- [ ] **Step 6.5: Write failing test — `list_profile_conflicts`**

  Create `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListProfileConflictsToolTest.java`:

  ```java
  package io.tiko.mcp.tools;

  import static org.assertj.core.api.Assertions.assertThat;

  import io.tiko.mcp.TopologyStore;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;
  import java.util.Map;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  class ListProfileConflictsToolTest {

      @Test
      void detectsSameInterfaceUnderDifferentProfiles(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[
                     {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["dev"]},
                     {"qualifiedName":"x.ProdImpl","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":["prod"]}
                   ],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """);
          var tool = new ListProfileConflictsTool(store);

          @SuppressWarnings("unchecked")
          List<Map<String, Object>> conflicts =
                  (List<Map<String, Object>>) tool.execute(Map.of()).get("conflicts");
          assertThat(conflicts).hasSize(1);
          var c = conflicts.get(0);
          assertThat(c.get("type")).isEqualTo("x.IThing");
          @SuppressWarnings("unchecked")
          var impls = (List<Map<String, Object>>) c.get("implementations");
          assertThat(impls).hasSize(2);
      }

      @Test
      void noConflictsWhenAllShareProfile(@TempDir Path root) throws Exception {
          var store = storeWith(root, """
                  {"schemaVersion":1,"module":"m",
                   "components":[
                     {"qualifiedName":"x.A","scope":"SINGLETON","interfaces":["x.IThing"],"profiles":[]},
                     {"qualifiedName":"x.B","scope":"SINGLETON","interfaces":["x.IOther"],"profiles":[]}
                   ],
                   "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                  """);
          var tool = new ListProfileConflictsTool(store);
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> conflicts =
                  (List<Map<String, Object>>) tool.execute(Map.of()).get("conflicts");
          assertThat(conflicts).isEmpty();
      }

      private TopologyStore storeWith(Path root, String json) throws Exception {
          var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
          Files.createDirectories(f.getParent());
          Files.writeString(f, json, StandardCharsets.UTF_8);
          return TopologyStore.loadFrom(root);
      }
  }
  ```

- [ ] **Step 6.6: Implement `ListProfileConflictsTool`**

  Create `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListProfileConflictsTool.java`:

  ```java
  package io.tiko.mcp.tools;

  import io.tiko.mcp.TopologyStore;
  import java.util.ArrayList;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.TreeMap;

  /**
   * MCP tool: groups components by (interface FQN, qualifier) and reports any group
   * whose entries span disjoint profiles — i.e. environment-pinned implementations
   * of the same type that an agent setting up multi-env config needs to know about.
   *
   * <p>Components with empty {@code profiles[]} (active under all profiles) are
   * collapsed into the "ANY" bucket and do not by themselves create a conflict;
   * they appear in a group only if another entry pins it to a specific profile.
   */
  public final class ListProfileConflictsTool {

      public static final String NAME = "list_profile_conflicts";

      private final TopologyStore store;

      public ListProfileConflictsTool(TopologyStore store) {
          this.store = store;
      }

      @SuppressWarnings("unchecked")
      public Map<String, Object> execute(Map<String, Object> args) {
          // Key: "<interfaceFqn>|<qualifier|null>".
          var groups = new TreeMap<String, List<Map<String, Object>>>();
          for (var c : store.components()) {
              var interfaces = (List<Object>) c.getOrDefault("interfaces", List.of());
              var qualifier = c.get("qualifier");
              for (var iface : interfaces) {
                  var k = iface + "|" + (qualifier == null ? "" : qualifier);
                  groups.computeIfAbsent(k, kk -> new ArrayList<>()).add(c);
              }
          }
          var conflicts = new ArrayList<Map<String, Object>>();
          for (var e : groups.entrySet()) {
              var impls = e.getValue();
              if (impls.size() < 2) continue;
              if (!hasDisjointProfiles(impls)) continue;
              var k = e.getKey().split("\\|", -1);
              var entry = new LinkedHashMap<String, Object>();
              entry.put("type", k[0]);
              entry.put("qualifier", k[1].isEmpty() ? null : k[1]);
              var implsOut = new ArrayList<Map<String, Object>>();
              for (var c : impls) {
                  var row = new LinkedHashMap<String, Object>();
                  row.put("qualifiedName", c.get("qualifiedName"));
                  row.put("profiles", c.getOrDefault("profiles", List.of()));
                  implsOut.add(row);
              }
              entry.put("implementations", implsOut);
              conflicts.add(entry);
          }
          var out = new LinkedHashMap<String, Object>();
          out.put("conflicts", conflicts);
          return out;
      }

      @SuppressWarnings("unchecked")
      private static boolean hasDisjointProfiles(List<Map<String, Object>> impls) {
          // Treat empty profiles ("ANY") as wildcard. A group is a conflict iff
          // at least two entries pin to distinct, non-empty profile sets.
          var seen = new java.util.HashSet<String>();
          for (var c : impls) {
              var profiles = (List<Object>) c.getOrDefault("profiles", List.of());
              if (profiles.isEmpty()) continue;
              for (var p : profiles) seen.add(p.toString());
          }
          return seen.size() >= 2;
      }
  }
  ```

- [ ] **Step 6.7: Run all tests to verify they pass**

  ```bash
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp test -Dtest=ListComponentsToolTest,ListProfileConflictsToolTest
  ```

  Expected: all PASS.

- [ ] **Step 6.8: Add `profile` arg to `explain_wiring`**

  In `ExplainWiringTool.execute(...)`, read `profile` from `args`; if present, filter `findComponent` lookups so that:
  - Exact `qualifiedName` matches still resolve (the component still exists in the topology even if inactive — but mark it inactive).
  - Interface-typed dep resolution prefers impls whose `profiles` is empty or includes the requested profile.

  Add a test:

  ```java
  @Test
  void respectsProfileWhenResolvingInterfaceDeps(@TempDir Path root) throws Exception {
      // Two impls of IThing, one dev, one prod. With profile=prod, walker picks ProdImpl.
      // [populate topology + assert tree.get(1).get("component").get("qualifiedName") == "x.ProdImpl"]
  }
  ```

  Implementation outline:

  ```java
  var profile = (String) args.get("profile");
  // pass through to findComponent
  private Map<String, Object> findComponent(String fqn, String profile) {
      // exact match first (unchanged)
      // interface match: prefer those whose profiles is empty or contains `profile`
  }
  ```

- [ ] **Step 6.9: Update example 13 with a profile pair**

  Add `tiko-examples/13_mcp_introspection/src/main/java/example/profiles/IGreeter.java`, `DevGreeter.java` (`profiles = {"dev"}`), `ProdGreeter.java` (`profiles = {"prod"}`). Inject `IGreeter` into a singleton so manual QA via `explain_wiring {componentFqn, profile: "prod"}` shows the prod impl.

- [ ] **Step 6.10: Wire new tool into server and bridge**

  Same pattern. Schemas:

  ```java
  var listProfileConflictsSchema = """
          {"type":"object","properties":{}}""";

  // Modify existing list_components and explain_wiring schemas to add `profile`:
  var listComponentsSchema = """
          {"type":"object","properties":{
             "scope":{"type":"string","enum":["SINGLETON","REQUEST","EVENT","PROTOTYPE"]},
             "interface":{"type":"string"},
             "profile":{"type":"string"}}}""";

  var explainWiringSchema = """
          {"type":"object","properties":{
             "componentFqn":{"type":"string"},
             "maxDepth":{"type":"integer","default":10},
             "profile":{"type":"string"}},
           "required":["componentFqn"]}""";
  ```

- [ ] **Step 6.11: Extend IT, format, build, commit, PR**

  Add `"list_profile_conflicts"` to `EXPECTED_TOOLS`. Then:

  ```bash
  W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply
  W:\tools\apache-maven\bin\mvn -pl tiko-mcp,tiko-examples/13_mcp_introspection clean test
  git add tiko-mcp/ tiko-examples/13_mcp_introspection/
  git commit -m "feat(mcp): profile-aware list_components/explain_wiring + list_profile_conflicts (#144)"
  git push -u origin mcp-profile-aware-queries
  "C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(mcp): profile-aware queries (#144)" --body "Closes #144. Adds \`profile\` filter to \`list_components\` and \`explain_wiring\`, plus new tool \`list_profile_conflicts\` that surfaces (interface, qualifier) pairs implemented under disjoint profiles. Example 13 ships a dev/prod IGreeter pair for manual QA."
  ```

---

## Wrap-up

After all six PRs merge (in the order above):

- [ ] **Wrap-up Step 1: Verify all milestone-3 MCP issues closed**

  ```bash
  "C:\Program Files\GitHub CLI\gh.exe" issue list --milestone "Phase 3 — Onboarding & tooling" --state open --label enhancement --json number,title
  ```

  Expected: none of #140–#145 remain.

- [ ] **Wrap-up Step 2: Exit and clean up the worktree**

  Use the `ExitWorktree` tool with `action: remove` after all branches are merged and pruned. Leaves `main` clean for the (separate) examples QA plan.

---

## Self-Review

**Spec coverage (per issue):**

- #145 reload: Task 1 covers `TopologyStore.reload()`, `ReloadTool`, server wiring, IT, response payload (`reloaded`, `topologyTimestamp`).
- #143 surface @Produces: Task 2 covers `list_components` PRODUCED entries with `producedBy`, `explain_wiring` producer-edge walking, example 13 update.
- #142 list_wiring_errors: Task 3 covers `WiringError` record, `WiringErrorsWriter`, ProcessorContext collector, MCP tool, `TopologyStore` load, schema doc update.
- #141 find_dependents: Task 4 covers direct + transitive reverse lookup, did-you-mean error.
- #140 trace_event_flow: Task 5 covers DAG walking, cycle detection, terminal marking, async/spread/guard surfacing per edge, unknown-event error.
- #144 profile filters + conflicts: Task 6 covers `profile` arg on `list_components` and `explain_wiring`, new `list_profile_conflicts` tool, example 13 dev/prod pair.

**Placeholder scan:** Step 3.2's "use Grep tool" sentence and Step 3.10's "mirror their structure" are the closest things to placeholders — these reference existing scaffolds (`compile-testing`-style tests already in `tiko-processor/src/test/`) rather than asking the implementer to invent. Acceptable: any reasonable implementer reading those existing tests will not be confused.

**Type consistency:** `ReloadTool.NAME = "reload"`, `ListWiringErrorsTool.NAME = "list_wiring_errors"`, `FindDependentsTool.NAME = "find_dependents"`, `TraceEventFlowTool.NAME = "trace_event_flow"`, `ListProfileConflictsTool.NAME = "list_profile_conflicts"` — match the issue text and `EXPECTED_TOOLS` extensions throughout. `producedBy.componentFqn / methodName / isStatic` field names used in both Tasks 2 (`projectedFromFactory`) and the issue acceptance criteria match. `wiring-errors.json` field names match `WiringError` record components.

**Branching:** Every task branches off `worktree-phase3-followups` (not `main`), so each PR opens against `main` from this worktree's branches. Six separate PRs per `feedback_pr_descriptions_scoped`.
