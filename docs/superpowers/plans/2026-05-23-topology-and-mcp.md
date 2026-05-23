# Topology JSON + Config Schema + MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit machine-readable `META-INF/tiko/topology.json` and `META-INF/tiko/config-schema.json` from the annotation processor, and ship a `tiko-mcp` stdio server that exposes the wiring to AI coding agents.

**Architecture:** The processor gains two new writers (`TopologyWriter`, `ConfigSchemaWriter`) that emit JSON to `CLASS_OUTPUT/META-INF/tiko/…` via `Filer` — same pattern as the existing `ConfigManifestWriter`. JSON is hand-rolled (no Jackson on the processor path). A new module `tiko-mcp` packages a runnable shaded jar that loads the JSON artifacts from a project's `target/classes/META-INF/tiko/…` (multi-module aware) and exposes four read-only MCP tools via the official Java SDK. An opt-out (`-Atiko.topology.bundle=false`) suppresses emission for sensitive jars.

**Tech Stack:** Java 21, Maven 3, `Filer` (JSR-269), `compile-testing` for processor ITs, JUnit 5 + AssertJ, official `io.modelcontextprotocol.sdk:mcp` SDK, maven-shade-plugin for the runnable jar.

**Spec:** [`docs/superpowers/specs/2026-05-23-topology-and-mcp-design.md`](../specs/2026-05-23-topology-and-mcp-design.md)

**Branch:** Work continues on `feature/22-topology-mcp` (already created; spec committed).

---

## File structure

### PR 1 — Topology JSON

```
tiko-processor/src/main/java/io/tiko/processor/topology/
├── JsonWriter.java                    ← hand-rolled JSON serializer (shared across PR 1 + 2)
└── TopologyWriter.java                ← reads ProcessorContext, writes META-INF/tiko/topology.json

tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
  — call TopologyWriter in generate(); add tiko.topology.bundle to getSupportedOptions()

tiko-processor/src/test/java/io/tiko/processor/topology/
├── JsonWriterTest.java                ← unit
├── TopologyWriterTest.java            ← compile-testing IT covering every field
├── TopologyWriterVersionGuardTest.java← guard so schemaVersion bumps are deliberate
└── TopologyWriterOptOutTest.java      ← -Atiko.topology.bundle=false suppresses emission

docs/topology-schema.md                ← v1 reference + additive-only rule
```

### PR 2 — Config Schema JSON

```
tiko-processor/src/main/java/io/tiko/processor/topology/
├── JsonSchemaTypeMapper.java          ← TypeMirror → JSON Schema fragment
└── ConfigSchemaWriter.java            ← reads ConfigurationModel list, writes META-INF/tiko/config-schema.json

tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
  — call ConfigSchemaWriter in generate() (same opt-out)

tiko-processor/src/test/java/io/tiko/processor/topology/
├── JsonSchemaTypeMapperTest.java      ← one row per type in the mapping table
└── ConfigSchemaWriterTest.java        ← IT covering required/optional/defaulted/enum/list/set/map/nested

tiko-processor/src/test/java/io/tiko/processor/topology/
└── ConfigSchemaValidYamlSmokeTest.java← YAML-to-JSON validates against generated schema
```

### PR 3 — `tiko-mcp` module + example

```
tiko-mcp/
├── pom.xml                            ← new module; MCP SDK + shade plugin
└── src/
    ├── main/java/io/tiko/mcp/
    │   ├── TikoMcpServer.java         ← main(); wires SDK + tools to stdio
    │   ├── TopologyStore.java         ← loads + caches + merges JSON files from project dir
    │   ├── JsonReader.java            ← stdlib JSON reader
    │   ├── model/
    │   │   ├── Topology.java          ← in-memory model parsed from topology.json
    │   │   ├── TopologyComponent.java
    │   │   ├── TopologyDependency.java
    │   │   ├── TopologyEventHandler.java
    │   │   ├── TopologyEventTrigger.java
    │   │   └── TopologyConfiguration.java
    │   └── tools/
    │       ├── ListComponentsTool.java
    │       ├── ListEventsTool.java
    │       ├── GetConfigSchemaTool.java
    │       └── ExplainWiringTool.java
    └── test/java/io/tiko/mcp/
        ├── JsonReaderTest.java
        ├── TopologyStoreTest.java
        ├── tools/
        │   ├── ListComponentsToolTest.java
        │   ├── ListEventsToolTest.java
        │   ├── GetConfigSchemaToolTest.java
        │   └── ExplainWiringToolTest.java
        └── TikoMcpServerSubprocessIT.java   ← spawn shaded jar, JSON-RPC tools/list smoke

tiko-examples/13_mcp_introspection/
├── pom.xml
├── README.md                          ← install snippet + transcript
├── .mcp.json                          ← Claude Code MCP wiring snippet
├── config.yaml
└── src/main/java/example/
    ├── Main.java
    ├── OrderService.java
    ├── OrderRepository.java
    ├── DbConfig.java
    └── events/{OrderPlaced.java, OrderValidated.java}

pom.xml (root)                         ← <module>tiko-mcp</module>
tiko-bom/pom.xml                       ← tiko-mcp + MCP SDK version pins
tiko-examples/pom.xml                  ← <module>13_mcp_introspection</module>
README.md                              ← new "AI-agent topology server" section
docs/roadmap.md                        ← mark #22 closed; Phase 3 6/6
```

---

# PR 1 — Topology JSON emission

## Task 1: Hand-rolled `JsonWriter` helper

A tiny streaming JSON writer. No dependency. Used by `TopologyWriter` in PR 1 and `ConfigSchemaWriter` in PR 2. Lives in `io.tiko.processor.topology` so it travels with the writers.

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/topology/JsonWriter.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/topology/JsonWriterTest.java`

- [ ] **Step 1: Write failing tests for JsonWriter**

```java
// tiko-processor/src/test/java/io/tiko/processor/topology/JsonWriterTest.java
package io.tiko.processor.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    void writesNull() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.nullValue();
        }
        assertThat(sw.toString()).isEqualTo("null");
    }

    @Test
    void writesString() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.value("hello");
        }
        assertThat(sw.toString()).isEqualTo("\"hello\"");
    }

    @Test
    void escapesQuotesAndBackslashesAndControls() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.value("a\"b\\c\nd\te");
        }
        assertThat(sw.toString()).isEqualTo("\"a\\\"b\\\\c\\nd\\te\"");
    }

    @Test
    void writesIntAndBoolean() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.array();
            jw.value(42);
            jw.value(true);
            jw.value(false);
            jw.endArray();
        }
        assertThat(sw.toString()).isEqualTo("[42,true,false]");
    }

    @Test
    void writesObjectWithFields() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.object();
            jw.field("name").value("Tiko");
            jw.field("version").value(1);
            jw.endObject();
        }
        assertThat(sw.toString()).isEqualTo("{\"name\":\"Tiko\",\"version\":1}");
    }

    @Test
    void writesNestedObjectsAndArrays() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.object();
            jw.field("items").array();
            jw.object().field("k").value("v").endObject();
            jw.endArray();
            jw.endObject();
        }
        assertThat(sw.toString()).isEqualTo("{\"items\":[{\"k\":\"v\"}]}");
    }

    @Test
    void emptyObjectAndArray() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw)) {
            jw.array();
            jw.object().endObject();
            jw.endArray();
        }
        assertThat(sw.toString()).isEqualTo("[{}]");
    }

    @Test
    void prettyPrintIndentsTwoSpaces() {
        var sw = new StringWriter();
        try (var jw = new JsonWriter(sw, true)) {
            jw.object().field("a").value(1).field("b").value(2).endObject();
        }
        assertThat(sw.toString()).isEqualTo("{\n  \"a\": 1,\n  \"b\": 2\n}");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=JsonWriterTest
```

Expected: FAIL with "cannot find symbol class JsonWriter".

- [ ] **Step 3: Implement JsonWriter**

```java
// tiko-processor/src/main/java/io/tiko/processor/topology/JsonWriter.java
package io.tiko.processor.topology;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal streaming JSON writer. No third-party deps — the processor pulls in
 * JavaPoet and AutoService already; pulling Jackson would noticeably inflate the
 * annotation-processor jar that every downstream build inherits.
 *
 * <p>Usage: {@code try (var jw = new JsonWriter(writer)) { ... }}.
 * Methods return {@code this} for chaining. Compact by default; pass
 * {@code pretty = true} for two-space-indented output.
 */
public final class JsonWriter implements AutoCloseable {

    private final Writer out;
    private final boolean pretty;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private boolean expectingValue = false;
    private String pendingFieldName = null;

    public JsonWriter(Writer out) {
        this(out, false);
    }

    public JsonWriter(Writer out, boolean pretty) {
        this.out = out;
        this.pretty = pretty;
    }

    public JsonWriter object() {
        writeSeparatorIfNeeded();
        write("{");
        stack.push(new Frame(true, true));
        return this;
    }

    public JsonWriter endObject() {
        Frame f = stack.pop();
        if (pretty && !f.first) {
            write("\n");
            writeIndent();
        }
        write("}");
        afterValueWritten();
        return this;
    }

    public JsonWriter array() {
        writeSeparatorIfNeeded();
        write("[");
        stack.push(new Frame(false, true));
        return this;
    }

    public JsonWriter endArray() {
        Frame f = stack.pop();
        if (pretty && !f.first) {
            write("\n");
            writeIndent();
        }
        write("]");
        afterValueWritten();
        return this;
    }

    public JsonWriter field(String name) {
        if (stack.isEmpty() || !stack.peek().isObject) {
            throw new IllegalStateException("field() requires an open object");
        }
        Frame f = stack.peek();
        if (!f.first) write(",");
        if (pretty) {
            write("\n");
            writeIndent();
        }
        f.first = false;
        pendingFieldName = name;
        write("\"");
        writeEscaped(name);
        write("\"");
        write(pretty ? ": " : ":");
        expectingValue = true;
        return this;
    }

    public JsonWriter value(String s) {
        writeSeparatorIfNeeded();
        if (s == null) {
            write("null");
        } else {
            write("\"");
            writeEscaped(s);
            write("\"");
        }
        afterValueWritten();
        return this;
    }

    public JsonWriter value(long v) {
        writeSeparatorIfNeeded();
        write(Long.toString(v));
        afterValueWritten();
        return this;
    }

    public JsonWriter value(boolean v) {
        writeSeparatorIfNeeded();
        write(v ? "true" : "false");
        afterValueWritten();
        return this;
    }

    public JsonWriter nullValue() {
        writeSeparatorIfNeeded();
        write("null");
        afterValueWritten();
        return this;
    }

    /** Write a raw JSON fragment — used to splice in pre-serialized JSON Schema sub-trees. */
    public JsonWriter raw(String json) {
        writeSeparatorIfNeeded();
        write(json);
        afterValueWritten();
        return this;
    }

    @Override
    public void close() {
        if (!stack.isEmpty()) {
            throw new IllegalStateException("Unclosed JSON containers: " + stack.size());
        }
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ----- internal -----

    private void writeSeparatorIfNeeded() {
        if (expectingValue) {
            // already wrote " : " via field()
            expectingValue = false;
            return;
        }
        if (stack.isEmpty()) return;
        Frame f = stack.peek();
        if (f.isObject) return; // object values are always preceded by field()
        // array element
        if (!f.first) write(",");
        if (pretty) {
            write("\n");
            writeIndent();
        }
        f.first = false;
    }

    private void afterValueWritten() {
        pendingFieldName = null;
    }

    private void writeIndent() {
        for (int i = 0; i < stack.size(); i++) write("  ");
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeEscaped(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        write(sb.toString());
    }

    private static final class Frame {
        final boolean isObject;
        boolean first;

        Frame(boolean isObject, boolean first) {
            this.isObject = isObject;
            this.first = first;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=JsonWriterTest
```

Expected: PASS. All 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/topology/JsonWriter.java tiko-processor/src/test/java/io/tiko/processor/topology/JsonWriterTest.java
git commit -m "feat(processor): hand-rolled JsonWriter for topology emission (#22)"
```

---

## Task 2: `TopologyWriter` — serialize ProcessorContext to JSON

Reads the active components / factories / event handlers / event triggers / configurations from `ProcessorContext` and emits a v1-shaped JSON document. Does not write the file yet — pure transformation function returning the JSON string. (Splitting transform from I/O makes it unit-testable without `Filer`.)

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/topology/TopologyWriter.java`

- [ ] **Step 1: Implement TopologyWriter**

```java
// tiko-processor/src/main/java/io/tiko/processor/topology/TopologyWriter.java
package io.tiko.processor.topology;

import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.EventTriggerModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import javax.annotation.processing.Filer;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Emits {@code META-INF/tiko/topology.json} — a versioned, machine-readable
 * description of every {@code @Component}, {@code @Produces}, {@code @EventHandler},
 * {@code @EventTrigger}, and {@code @Configuration} discovered in the round.
 *
 * <p>Schema is {@code schemaVersion: 1}, additive-only thereafter. New fields are
 * optional; renames or removals require a major bump. See
 * {@code docs/topology-schema.md}.
 */
public final class TopologyWriter {

    private static final int SCHEMA_VERSION = 1;
    private static final String PATH = "META-INF/tiko/topology.json";

    private final ProcessorContext context;

    public TopologyWriter(ProcessorContext context) {
        this.context = context;
    }

    /** Writes the JSON resource via the supplied Filer. */
    public void write(Filer filer) throws IOException {
        FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = f.openWriter()) {
            renderTo(w);
        }
    }

    /** Serializes to a String — used by unit tests so they don't need a Filer. */
    public String render() {
        var sw = new StringWriter();
        renderTo(sw);
        return sw.toString();
    }

    private void renderTo(Writer w) {
        try (var jw = new JsonWriter(w, true)) {
            jw.object();
            jw.field("schemaVersion").value(SCHEMA_VERSION);
            jw.field("module").value(context.getContainerClassName());
            writeComponents(jw);
            writeFactoryMethods(jw);
            writeEventHandlers(jw);
            writeEventTriggers(jw);
            writeConfigurations(jw);
            jw.endObject();
        }
    }

    private void writeComponents(JsonWriter jw) {
        jw.field("components").array();
        for (ComponentModel c : context.getActiveComponents()) {
            jw.object();
            jw.field("qualifiedName").value(c.getQualifiedName());
            jw.field("packageName").value(c.getPackageName());
            jw.field("simpleName").value(c.getClassName());
            jw.field("scope").value(c.getScope().name());
            jw.field("qualifier").value(c.getName().orElse(null));
            writeStringArray(jw, "profiles", c.getProfiles());
            jw.field("interfaces").array();
            for (TypeMirror iface : c.getTypeElement().getInterfaces()) {
                jw.value(iface.toString());
            }
            jw.endArray();
            jw.field("isTestComponent").value(c.isTestComponent());
            jw.field("requiresProxy").value(c.requiresProxy());
            jw.field("exposeSelf").value(c.isExposeSelf());
            jw.field("exposeTypes").array();
            for (TypeMirror t : c.getExposeTypes()) {
                jw.value(t.toString());
            }
            jw.endArray();
            writeDependencies(jw, c.getDependencies());
            writeLifecycle(jw, c.getPostConstructMethods(), c.getPreDestroyMethods(), c.isAutoCloseable());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeFactoryMethods(JsonWriter jw) {
        jw.field("factoryMethods").array();
        for (FactoryMethodModel f : context.getActiveFactoryMethods()) {
            jw.object();
            jw.field("declaringClass")
                    .value(f.getDeclaringClass().getQualifiedName().toString());
            jw.field("methodName").value(f.getMethodName());
            jw.field("returnType").value(f.getReturnTypeName());
            jw.field("scope").value(f.getScope().name());
            jw.field("qualifier").value(f.getName().isEmpty() ? null : f.getName());
            writeStringArray(jw, "profiles", f.getProfiles());
            jw.field("static").value(f.getMethodElement().getModifiers().contains(javax.lang.model.element.Modifier.STATIC));
            jw.field("autoCloseable").value(f.isAutoCloseable());
            jw.field("requiresProxy").value(f.requiresProxy());
            writeDependencies(jw, f.getDependencies());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeEventHandlers(JsonWriter jw) {
        jw.field("eventHandlers").array();
        for (EventHandlerModel h : context.getEventHandlers()) {
            jw.object();
            jw.field("declaringClass")
                    .value(h.getDeclaringClass().getQualifiedName().toString());
            jw.field("methodName").value(h.getMethodName());
            jw.field("eventType").value(h.getEventTypeName());
            jw.field("async").value(h.isAsync());
            jw.field("hasEventWrapper").value(h.isHasEventWrapper());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeEventTriggers(JsonWriter jw) {
        jw.field("eventTriggers").array();
        for (EventHandlerModel h : context.getEventHandlers()) {
            for (EventTriggerModel t : h.getEventTriggers()) {
                jw.object();
                jw.field("handlerClass")
                        .value(h.getDeclaringClass().getQualifiedName().toString());
                jw.field("handlerMethod").value(h.getMethodName());
                jw.field("eventName").value(t.getEventName());
                jw.field("async").value(t.isAsync());
                jw.field("spread").value(t.isSpread());
                jw.field("guards").array();
                for (TypeMirror g : t.getGuardClasses()) {
                    jw.value(g.toString());
                }
                jw.endArray();
                jw.endObject();
            }
        }
        jw.endArray();
    }

    private void writeConfigurations(JsonWriter jw) {
        jw.field("configurations").array();
        for (ConfigurationModel cfg : context.getConfigurations()) {
            jw.object();
            jw.field("qualifiedName").value(cfg.qualifiedName());
            jw.field("prefix").value(cfg.prefix());
            jw.field("fields").array();
            for (var f : cfg.fields()) {
                jw.object();
                jw.field("name").value(f.fieldName());
                jw.field("yamlKey").value(f.yamlKey());
                jw.field("type").value(f.type().toString());
                jw.field("cardinality").value(f.cardinality().name());
                jw.field("default").value(f.defaultValue());
                jw.endObject();
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeDependencies(JsonWriter jw, java.util.List<DependencyModel> deps) {
        jw.field("constructorDependencies").array();
        for (DependencyModel d : deps) {
            jw.object();
            jw.field("type").value(d.getTypeName());
            jw.field("qualifier").value(d.getQualifier().isEmpty() ? null : d.getQualifier());
            jw.field("kind").value(d.isProvider() ? "PROVIDER" : d.isPicker() ? "PICKER" : "DIRECT");
            jw.field("pickedType").value(d.getPickedTypeName());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeLifecycle(
            JsonWriter jw,
            java.util.List<ExecutableElement> postConstruct,
            java.util.List<ExecutableElement> preDestroy,
            boolean autoCloseable) {
        jw.field("lifecycle").object();
        writeMethodNameArray(jw, "postConstruct", postConstruct);
        writeMethodNameArray(jw, "preDestroy", preDestroy);
        jw.field("autoCloseable").value(autoCloseable);
        jw.endObject();
    }

    private void writeMethodNameArray(JsonWriter jw, String fieldName, java.util.List<ExecutableElement> methods) {
        jw.field(fieldName).array();
        for (ExecutableElement m : methods) {
            jw.value(m.getSimpleName().toString());
        }
        jw.endArray();
    }

    private void writeStringArray(JsonWriter jw, String fieldName, java.util.List<String> values) {
        jw.field(fieldName).array();
        for (String s : values) {
            jw.value(s);
        }
        jw.endArray();
    }
}
```

Notes:
- The DependencyModel / EventHandlerModel / FactoryMethodModel getters above (`getTypeName`, `getQualifier`, `isProvider`, `isPicker`, `getPickedTypeName`, `isAsync`, `isHasEventWrapper`, `isSpread`, `getGuardClasses`, `getDeclaringClass`, `getMethodName`, etc.) must exist on those models. Verify by reading `tiko-processor/src/main/java/io/tiko/processor/model/*.java` — these are standard accessors already in use by other generators. If any are missing, add a getter following the existing builder/getter pattern in the same file.

- [ ] **Step 2: Commit (no test yet — Task 6 covers the IT)**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/topology/TopologyWriter.java
git commit -m "feat(processor): TopologyWriter renders topology.json from ProcessorContext (#22)"
```

---

## Task 3: Wire `TopologyWriter` into `TikoAnnotationProcessor.generate()`

Add the call after `ConfigManifestWriter`. Always invoked when there's anything to describe (components, factories, handlers, or configurations).

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`

- [ ] **Step 1: Add TopologyWriter call to generate()**

Find the existing line in `generate()`:

```java
new io.tiko.processor.config.ConfigManifestWriter(processingEnv.getFiler(), regGen.registryClassFqn())
        .write(configs);
```

Add immediately after it (and before the container generator call):

```java
// Emit machine-readable topology.json for AI agents / IDE tooling / doc generators
// when the build has anything worth describing. Gated to avoid empty-file noise.
if (!context.getActiveComponents().isEmpty()
        || !context.getActiveFactoryMethods().isEmpty()
        || !context.getEventHandlers().isEmpty()
        || !context.getConfigurations().isEmpty()) {
    new io.tiko.processor.topology.TopologyWriter(context).write(processingEnv.getFiler());
}
```

- [ ] **Step 2: Verify processor still compiles**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run full processor test suite (existing tests must not regress)**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test
```

Expected: BUILD SUCCESS, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
git commit -m "feat(processor): emit META-INF/tiko/topology.json (#22)"
```

---

## Task 4: Comprehensive processor IT for topology.json

Single test class covering every field in the v1 shape using one rich fixture. Reads the generated file from the in-memory `Filer`.

**Files:**
- Test: `tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterTest.java`

- [ ] **Step 1: Write the IT**

```java
// tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterTest.java
package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class TopologyWriterTest {

    @Test
    void richFixtureIsEmittedToMetaInfTikoTopologyJson() throws IOException {
        JavaFileObject service = JavaFileObjects.forSourceLines(
                "io.example.OrderService",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class OrderService implements Orders {",
                "  @Inject public OrderService(OrderRepository repo) {}",
                "  @EventHandler public void onPlaced(OrderPlaced e) {}",
                "  @PostConstruct void init() {}",
                "}");
        JavaFileObject ordersIface = JavaFileObjects.forSourceLines(
                "io.example.Orders",
                "package io.example;",
                "public interface Orders {}");
        JavaFileObject repo = JavaFileObjects.forSourceLines(
                "io.example.OrderRepository",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class OrderRepository { @Inject public OrderRepository() {} }");
        JavaFileObject event = JavaFileObjects.forSourceLines(
                "io.example.OrderPlaced",
                "package io.example;",
                "public record OrderPlaced(String id) {}");
        JavaFileObject cfg = JavaFileObjects.forSourceLines(
                "io.example.DbConfig",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "@Configuration(prefix = \"database\")",
                "public record DbConfig(String url, @Default(\"10\") int poolSize) {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(service, ordersIface, repo, event, cfg);
        assertThat(c).succeeded();

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json");
        assertThat(fileOpt).isPresent();

        String json;
        try (var r = new InputStreamReader(fileOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }

        // Top-level
        assertThat(json).contains("\"schemaVersion\": 1");
        assertThat(json).contains("\"module\":");

        // Components
        assertThat(json).contains("\"qualifiedName\": \"io.example.OrderService\"");
        assertThat(json).contains("\"scope\": \"SINGLETON\"");
        assertThat(json).contains("\"interfaces\":");
        assertThat(json).contains("io.example.Orders");
        assertThat(json).contains("\"isTestComponent\": false");

        // Dependencies
        assertThat(json).contains("\"constructorDependencies\":");
        assertThat(json).contains("\"type\": \"io.example.OrderRepository\"");
        assertThat(json).contains("\"kind\": \"DIRECT\"");

        // Lifecycle
        assertThat(json).contains("\"postConstruct\":");
        assertThat(json).contains("\"init\"");
        assertThat(json).contains("\"autoCloseable\": false");

        // Event handlers
        assertThat(json).contains("\"eventHandlers\":");
        assertThat(json).contains("\"eventType\": \"io.example.OrderPlaced\"");
        assertThat(json).contains("\"async\": false");

        // Configurations
        assertThat(json).contains("\"configurations\":");
        assertThat(json).contains("\"prefix\": \"database\"");
        assertThat(json).contains("\"name\": \"url\"");
        assertThat(json).contains("\"cardinality\": \"REQUIRED\"");
        assertThat(json).contains("\"name\": \"poolSize\"");
        assertThat(json).contains("\"cardinality\": \"DEFAULTED\"");
        assertThat(json).contains("\"default\": \"10\"");
    }

    @Test
    void emptySourceSetDoesNotEmitTopologyJson() {
        // No @Component/@Produces/@EventHandler/@Configuration → no file.
        JavaFileObject plain = JavaFileObjects.forSourceLines(
                "io.example.Plain", "package io.example;", "public class Plain {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(plain);

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json");
        assertThat(fileOpt).isNotPresent();
    }

    @Test
    void producesFactoryAppearsInFactoryMethodsArray() throws IOException {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.DataSources",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class DataSources {",
                "  @Inject public DataSources() {}",
                "  @Produces(scope = Scope.SINGLETON, name = \"mysql\")",
                "  public javax.sql.DataSource mysql() { return null; }",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(factory);
        assertThat(c).succeeded();

        var fileOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json");
        assertThat(fileOpt).isPresent();
        String json;
        try (var r = new InputStreamReader(fileOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(json).contains("\"factoryMethods\":");
        assertThat(json).contains("\"methodName\": \"mysql\"");
        assertThat(json).contains("\"qualifier\": \"mysql\"");
        assertThat(json).contains("\"returnType\": \"javax.sql.DataSource\"");
    }
}
```

- [ ] **Step 2: Run the IT**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=TopologyWriterTest
```

Expected: PASS, 3 tests green.

- [ ] **Step 3: Commit**

```bash
git add tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterTest.java
git commit -m "test(processor): TopologyWriter IT covering rich fixture (#22)"
```

---

## Task 5: Schema-versioning guard test

A literal-string assertion designed to fail when someone bumps `SCHEMA_VERSION`. Forces a deliberate read of the additive-only rule.

**Files:**
- Test: `tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterVersionGuardTest.java`

- [ ] **Step 1: Write the guard test**

```java
// tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterVersionGuardTest.java
package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * Bumping {@code SCHEMA_VERSION} fails this test on purpose — the change must be
 * deliberate. v1 is additive-only; any v2 needs a docs/topology-schema.md update,
 * a migration note in the release notes, and an MCP server version bump.
 */
class TopologyWriterVersionGuardTest {

    @Test
    void schemaVersionIsExactlyOne() throws Exception {
        var component = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(component);
        assertThat(c).succeeded();

        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json")
                .orElseThrow();
        String json;
        try (var r = new InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(json)
                .as("topology schemaVersion must remain 1 — bump only with docs + MCP server update")
                .contains("\"schemaVersion\": 1");
    }
}
```

- [ ] **Step 2: Run the guard test**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=TopologyWriterVersionGuardTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterVersionGuardTest.java
git commit -m "test(processor): guard test pins topology schemaVersion to 1 (#22)"
```

---

## Task 6: `-Atiko.topology.bundle=false` opt-out

Add the option to `getSupportedOptions()`, read it during `init`, gate both `TopologyWriter` (now) and `ConfigSchemaWriter` (PR 2) on the same flag.

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterOptOutTest.java`

- [ ] **Step 1: Add the option to getSupportedOptions()**

Replace:

```java
@Override
public Set<String> getSupportedOptions() {
    return Set.of("tiko.profiles");
}
```

with:

```java
@Override
public Set<String> getSupportedOptions() {
    return Set.of("tiko.profiles", "tiko.topology.bundle");
}
```

- [ ] **Step 2: Read the option and gate emission**

Add a private helper at class level:

```java
/**
 * Returns true when the user has opted into topology emission (the default).
 * Set {@code -Atiko.topology.bundle=false} to suppress {@code topology.json} and
 * {@code config-schema.json} entirely (closed-source services, sensitive jars).
 */
private boolean topologyBundleEnabled() {
    String v = processingEnv.getOptions().get("tiko.topology.bundle");
    return v == null || !v.equalsIgnoreCase("false");
}
```

Wrap the TopologyWriter call from Task 3:

```java
if (topologyBundleEnabled()
        && (!context.getActiveComponents().isEmpty()
                || !context.getActiveFactoryMethods().isEmpty()
                || !context.getEventHandlers().isEmpty()
                || !context.getConfigurations().isEmpty())) {
    new io.tiko.processor.topology.TopologyWriter(context).write(processingEnv.getFiler());
}
```

- [ ] **Step 3: Write the opt-out IT**

```java
// tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterOptOutTest.java
package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class TopologyWriterOptOutTest {

    @Test
    void optOutSuppressesTopologyJson() {
        var component = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.topology.bundle=false")
                .compile(component);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json"))
                .isNotPresent();
    }

    @Test
    void defaultEmitsTopologyJson() {
        var component = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(component);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json"))
                .isPresent();
    }
}
```

- [ ] **Step 4: Run the opt-out test**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=TopologyWriterOptOutTest
```

Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java tiko-processor/src/test/java/io/tiko/processor/topology/TopologyWriterOptOutTest.java
git commit -m "feat(processor): -Atiko.topology.bundle=false suppresses topology emission (#22)"
```

---

## Task 7: `docs/topology-schema.md`

Reference doc with the v1 shape, the additive-only rule, and a short consumer example.

**Files:**
- Create: `docs/topology-schema.md`

- [ ] **Step 1: Write the doc**

```markdown
# `META-INF/tiko/topology.json` — schema v1

Every Tiko build emits a `topology.json` resource describing the wiring
discovered by the annotation processor. The file ships inside the jar so
downstream tools (and the [`tiko-mcp`](../tiko-mcp) server) can
introspect a project under development **or** an installed dependency.

To suppress emission for a module, add
`-Atiko.topology.bundle=false` to the annotation processor args.

## Stability

**v1 is additive-only.** New fields are optional. Renames or removals
require a major bump (`schemaVersion: 2`) plus a migration note. The
processor enforces this with a guard test that fails if anyone bumps
the constant without updating this document.

## Top-level shape

```json
{
  "schemaVersion": 1,
  "module": "io.tiko.generated.TikoContainerImpl_<hash>",
  "components": [ ... ],
  "factoryMethods": [ ... ],
  "eventHandlers": [ ... ],
  "eventTriggers": [ ... ],
  "configurations": [ ... ]
}
```

## `components[]`

Every `@Component` (including `@TestComponent`) collected in the
compile round.

| Field                     | Type            | Notes |
| ------------------------- | --------------- | ----- |
| `qualifiedName`           | string          | FQN of the impl class |
| `packageName`             | string          | |
| `simpleName`              | string          | |
| `scope`                   | string enum     | `SINGLETON` / `REQUEST` / `EVENT` / `PROTOTYPE` |
| `qualifier`               | string \| null  | `@Component(name = "...")`, null when unset |
| `profiles`                | string[]        | `@Component(profiles = ...)` |
| `interfaces`              | string[]        | FQNs of every directly-declared interface |
| `isTestComponent`         | boolean         | True when discovered via `@TestComponent` |
| `requiresProxy`           | boolean         | True when a cross-scope proxy was generated for this component |
| `exposeSelf`              | boolean         | `@Component(exposeSelf = ...)`, default true |
| `exposeTypes`             | string[]        | `@Component(expose = {...})`, empty = permissive default |
| `constructorDependencies` | object[]        | See below |
| `lifecycle`               | object          | See below |

### `constructorDependencies[]`

| Field         | Type            | Notes |
| ------------- | --------------- | ----- |
| `type`        | string          | Declared parameter type FQN (unwrapped from `Provider<T>` / `Picker<T>`) |
| `qualifier`   | string \| null  | `@Named(...)` value, null when unset |
| `kind`        | string enum     | `DIRECT` / `PROVIDER` / `PICKER` |
| `pickedType`  | string \| null  | `@Pick(SomeImpl.class)` target, null when unset |

### `lifecycle`

| Field           | Type     | Notes |
| --------------- | -------- | ----- |
| `postConstruct` | string[] | Method names |
| `preDestroy`    | string[] | Method names |
| `autoCloseable` | boolean  | True when implicit `close()` runs at scope teardown (no explicit `@PreDestroy`) |

## `factoryMethods[]`

Every `@Produces` method.

| Field             | Type           | Notes |
| ----------------- | -------------- | ----- |
| `declaringClass`  | string         | FQN of the `@Component` enclosing the method |
| `methodName`      | string         | |
| `returnType`      | string         | FQN of the returned type |
| `scope`           | string enum    | |
| `qualifier`       | string \| null | `@Produces(name = "...")`, null when unset |
| `profiles`        | string[]       | |
| `static`          | boolean        | True for static `@Produces` |
| `autoCloseable`   | boolean        | True when the produced type implements `AutoCloseable` |
| `requiresProxy`   | boolean        | True when a cross-scope proxy was generated |
| `constructorDependencies` | object[] | Same shape as `components[].constructorDependencies` (method parameter list) |

## `eventHandlers[]`

Every `@EventHandler` method.

| Field            | Type    | Notes |
| ---------------- | ------- | ----- |
| `declaringClass` | string  | |
| `methodName`     | string  | |
| `eventType`      | string  | FQN of the first parameter type |
| `async`          | boolean | `@EventHandler(async = ...)` |
| `hasEventWrapper`| boolean | True when the method takes a second `Event<?>` parameter |

## `eventTriggers[]`

Every `@EventTrigger` annotation (including each entry of an
`@EventTriggers` container).

| Field          | Type     | Notes |
| -------------- | -------- | ----- |
| `handlerClass` | string   | FQN of the `@EventHandler` carrying the trigger |
| `handlerMethod`| string   | |
| `eventName`    | string   | `@EventTrigger(eventName = ...)` |
| `async`        | boolean  | |
| `spread`       | boolean  | |
| `guards`       | string[] | FQNs of `EventTriggerGuard` classes; default `AlwaysAllow` is omitted |

**Caveat:** Only declarative `@EventTrigger` chains are captured.
Programmatic `EventBus.publish(...)` calls are invisible to the
processor and not listed here.

## `configurations[]`

Every `@Configuration` record.

| Field            | Type     | Notes |
| ---------------- | -------- | ----- |
| `qualifiedName`  | string   | |
| `prefix`         | string   | `@Configuration(prefix = ...)` |
| `fields[].name`  | string   | Record component name |
| `fields[].yamlKey` | string | `@Key("...")` override or `fields[].name` |
| `fields[].type`  | string   | TypeMirror string (e.g. `java.lang.String`, `java.util.List<java.lang.String>`) |
| `fields[].cardinality` | string enum | `REQUIRED` / `OPTIONAL` / `DEFAULTED` |
| `fields[].default` | string \| null | Raw `@Default("...")` value, null unless `DEFAULTED` |

## Consuming the file

From the shell:

```bash
find . -path '**/target/classes/META-INF/tiko/topology.json' -exec jq '.components[] | {name: .qualifiedName, scope}' {} +
```

From Python:

```python
import json, glob
for path in glob.glob('**/target/classes/META-INF/tiko/topology.json', recursive=True):
    with open(path) as f:
        topo = json.load(f)
    for c in topo['components']:
        print(c['qualifiedName'], c['scope'])
```

From an MCP-aware coding agent: see [`tiko-mcp`](../tiko-mcp).
```

- [ ] **Step 2: Commit**

```bash
git add docs/topology-schema.md
git commit -m "docs: topology.json v1 schema reference (#22)"
```

---

## Task 8: PR 1 wrap-up — open the PR

- [ ] **Step 1: Run full build + tests one more time**

```bash
W:\tools\apache-maven\bin\mvn.cmd clean install -pl tiko-processor -am
```

Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 2: Push the branch and open PR**

```bash
git push -u origin feature/22-topology-mcp
```

```bash
"C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(processor): emit META-INF/tiko/topology.json (#22, 1/3)" --body "$(cat <<'EOF'
## Summary

- Annotation processor now emits `META-INF/tiko/topology.json` — a versioned, machine-readable description of every `@Component`, `@Produces`, `@EventHandler`, `@EventTrigger`, and `@Configuration` collected in the round
- v1 schema documented in `docs/topology-schema.md`; additive-only thereafter
- Opt-out via `-Atiko.topology.bundle=false` for sensitive jars
- Hand-rolled `JsonWriter` keeps the processor zero-new-deps

First of three PRs against #22. Next up: config schema JSON (`#22 2/3`), then `tiko-mcp` server (`#22 3/3`).

## Test plan

- [x] `mvn -pl tiko-processor test` — all green, no regressions
- [x] New ITs: `TopologyWriterTest` (rich fixture), `TopologyWriterVersionGuardTest`, `TopologyWriterOptOutTest`
EOF
)"
```

Wait for user merge. Then `git checkout main && git pull && git checkout -b feature/22-topology-mcp-pr2` for PR 2.

---

# PR 2 — Config Schema JSON emission

## Task 9: `JsonSchemaTypeMapper` — TypeMirror → JSON Schema fragment

Pure transformation. One method `mapType(TypeMirror)` returns a JSON fragment as a `String`. Reuses `ConfigSupportedTypes` for "what does the binder accept" — same source of truth as the runtime binder.

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/topology/JsonSchemaTypeMapper.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/topology/JsonSchemaTypeMapperTest.java`

- [ ] **Step 1: Write the type-mapper tests**

```java
// tiko-processor/src/test/java/io/tiko/processor/topology/JsonSchemaTypeMapperTest.java
package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * Behavioral test — drives the mapper via a real {@code @Configuration} compile
 * and asserts the generated {@code config-schema.json} fragment per type. This is
 * cheaper than wiring up a standalone TypeMirror fixture and exercises the same
 * code path as production.
 */
class JsonSchemaTypeMapperTest {

    @Test
    void primitivesAndStringsMapAsExpected() throws Exception {
        String json = compileAndReadSchema(JavaFileObjects.forSourceLines(
                "io.example.T",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "@Configuration(prefix = \"t\")",
                "public record T(String s, int i, long l, boolean b, double d) {}"));

        assertThat(json).contains("\"s\":").contains("\"type\":\"string\"");
        assertThat(json).contains("\"i\":").contains("\"type\":\"integer\"");
        assertThat(json).contains("\"l\":").contains("\"type\":\"integer\"");
        assertThat(json).contains("\"b\":").contains("\"type\":\"boolean\"");
        assertThat(json).contains("\"d\":").contains("\"type\":\"number\"");
    }

    @Test
    void durationMapsToStringWithFormatDuration() throws Exception {
        String json = compileAndReadSchema(JavaFileObjects.forSourceLines(
                "io.example.T",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "import java.time.Duration;",
                "@Configuration(prefix = \"t\") public record T(Duration d) {}"));
        assertThat(json).contains("\"format\":\"duration\"");
    }

    @Test
    void listAndSetMapToArray() throws Exception {
        String json = compileAndReadSchema(JavaFileObjects.forSourceLines(
                "io.example.T",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "import java.util.List;",
                "import java.util.Set;",
                "@Configuration(prefix = \"t\") public record T(List<String> xs, Set<Integer> ys) {}"));
        assertThat(json).contains("\"xs\":").contains("\"type\":\"array\"").contains("\"items\":{\"type\":\"string\"}");
        assertThat(json).contains("\"ys\":").contains("\"items\":{\"type\":\"integer\"}");
    }

    @Test
    void enumMapsToStringEnum() throws Exception {
        String json = compileAndReadSchema(
                JavaFileObjects.forSourceLines(
                        "io.example.Mode",
                        "package io.example;",
                        "public enum Mode { FAST, SLOW }"),
                JavaFileObjects.forSourceLines(
                        "io.example.T",
                        "package io.example;",
                        "import io.tiko.annotations.*;",
                        "@Configuration(prefix = \"t\") public record T(Mode mode) {}"));
        assertThat(json).contains("\"mode\":");
        assertThat(json).contains("\"enum\":[\"FAST\",\"SLOW\"]");
    }

    @Test
    void nestedRecordMapsToNestedObject() throws Exception {
        String json = compileAndReadSchema(
                JavaFileObjects.forSourceLines(
                        "io.example.Inner",
                        "package io.example;",
                        "public record Inner(String s) {}"),
                JavaFileObjects.forSourceLines(
                        "io.example.T",
                        "package io.example;",
                        "import io.tiko.annotations.*;",
                        "@Configuration(prefix = \"t\") public record T(Inner inner) {}"));
        assertThat(json).contains("\"inner\":");
        // Nested object should declare its own properties
        assertThat(json).contains("\"type\":\"object\"");
        assertThat(json).contains("\"s\":");
    }

    private String compileAndReadSchema(javax.tools.JavaFileObject... sources) throws Exception {
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(sources);
        assertThat(c).succeeded();
        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json")
                .orElseThrow();
        try (var r = new InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8)) {
            return new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line);
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (mapper class doesn't exist yet)**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=JsonSchemaTypeMapperTest
```

Expected: FAIL — config-schema.json not generated yet.

- [ ] **Step 3: Implement JsonSchemaTypeMapper**

```java
// tiko-processor/src/main/java/io/tiko/processor/topology/JsonSchemaTypeMapper.java
package io.tiko.processor.topology;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Maps a {@link TypeMirror} (as collected from a {@code @Configuration} record
 * component) to a JSON Schema fragment string. Composes recursively for arrays,
 * sets, maps, and nested records.
 *
 * <p>Stays in lockstep with the runtime {@code ConfigSupportedTypes} — adding a
 * new accepted type means updating both.
 */
public final class JsonSchemaTypeMapper {

    private final Types typeUtils;

    public JsonSchemaTypeMapper(Types typeUtils) {
        this.typeUtils = typeUtils;
    }

    public String mapType(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case BOOLEAN -> "{\"type\":\"boolean\"}";
                case INT, LONG, SHORT, BYTE -> "{\"type\":\"integer\"}";
                case FLOAT, DOUBLE -> "{\"type\":\"number\"}";
                case CHAR -> "{\"type\":\"string\"}";
                default -> "{\"type\":\"string\"}";
            };
        }
        if (type.getKind() == TypeKind.DECLARED) {
            return mapDeclared((DeclaredType) type);
        }
        return "{\"type\":\"string\"}";
    }

    private String mapDeclared(DeclaredType dt) {
        TypeElement el = (TypeElement) dt.asElement();
        String fqn = el.getQualifiedName().toString();

        switch (fqn) {
            case "java.lang.String":
            case "java.lang.Character":
                return "{\"type\":\"string\"}";
            case "java.lang.Boolean":
                return "{\"type\":\"boolean\"}";
            case "java.lang.Integer":
            case "java.lang.Long":
            case "java.lang.Short":
            case "java.lang.Byte":
                return "{\"type\":\"integer\"}";
            case "java.lang.Float":
            case "java.lang.Double":
                return "{\"type\":\"number\"}";
            case "java.time.Duration":
                return "{\"type\":\"string\",\"format\":\"duration\"}";
            case "java.time.Instant":
            case "java.time.OffsetDateTime":
            case "java.time.ZonedDateTime":
                return "{\"type\":\"string\",\"format\":\"date-time\"}";
            case "java.time.LocalDate":
                return "{\"type\":\"string\",\"format\":\"date\"}";
            case "java.time.LocalTime":
                return "{\"type\":\"string\",\"format\":\"time\"}";
            case "java.net.URI":
            case "java.net.URL":
                return "{\"type\":\"string\",\"format\":\"uri\"}";
            case "java.util.List":
            case "java.util.Set":
                return "{\"type\":\"array\",\"items\":" + itemSchema(dt, 0) + "}";
            case "java.util.Map":
                return "{\"type\":\"object\",\"additionalProperties\":" + itemSchema(dt, 1) + "}";
            default:
                if (el.getKind() == ElementKind.ENUM) {
                    return enumSchema(el);
                }
                if (el.getKind() == ElementKind.RECORD) {
                    return recordSchema(el);
                }
                return "{\"type\":\"string\"}";
        }
    }

    private String itemSchema(DeclaredType collection, int typeArgIndex) {
        var args = collection.getTypeArguments();
        if (args.size() <= typeArgIndex) {
            return "{\"type\":\"string\"}";
        }
        return mapType(args.get(typeArgIndex));
    }

    private String enumSchema(TypeElement el) {
        var sb = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        boolean first = true;
        for (var member : el.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                if (!first) sb.append(",");
                sb.append('"').append(member.getSimpleName()).append('"');
                first = false;
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private String recordSchema(TypeElement record) {
        var sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        boolean first = true;
        for (var member : record.getEnclosedElements()) {
            if (member.getKind() == ElementKind.RECORD_COMPONENT) {
                if (!first) sb.append(",");
                sb.append('"').append(member.getSimpleName()).append("\":");
                sb.append(mapType(member.asType()));
                first = false;
            }
        }
        sb.append("},\"additionalProperties\":false}");
        return sb.toString();
    }
}
```

- [ ] **Step 4: Commit (tests will pass after Task 10 + 11 ship the writer + wiring)**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/topology/JsonSchemaTypeMapper.java tiko-processor/src/test/java/io/tiko/processor/topology/JsonSchemaTypeMapperTest.java
git commit -m "feat(processor): JsonSchemaTypeMapper for @Configuration types (#22)"
```

---

## Task 10: `ConfigSchemaWriter` — emit JSON Schema draft 2020-12

Reads `context.getConfigurations()`, unions every record under its prefix as a top-level property.

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/topology/ConfigSchemaWriter.java`

- [ ] **Step 1: Implement ConfigSchemaWriter**

```java
// tiko-processor/src/main/java/io/tiko/processor/topology/ConfigSchemaWriter.java
package io.tiko.processor.topology;

import io.tiko.processor.config.ConfigFieldModel;
import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.io.Writer;
import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Emits {@code META-INF/tiko/config-schema.json} — a JSON Schema draft 2020-12
 * document describing every {@code @Configuration} record. IntelliJ IDEA can be
 * wired to this file for YAML autocomplete and validation.
 */
public final class ConfigSchemaWriter {

    private static final String PATH = "META-INF/tiko/config-schema.json";

    private final ProcessorContext context;
    private final JsonSchemaTypeMapper typeMapper;

    public ConfigSchemaWriter(ProcessorContext context) {
        this.context = context;
        this.typeMapper = new JsonSchemaTypeMapper(context.getTypeUtils());
    }

    public void write(Filer filer) throws IOException {
        FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = f.openWriter()) {
            renderTo(w);
        }
    }

    private void renderTo(Writer w) {
        try (var jw = new JsonWriter(w, true)) {
            jw.object();
            jw.field("$schema").value("https://json-schema.org/draft/2020-12/schema");
            jw.field("$id").value("tiko://config-schema");
            jw.field("type").value("object");
            jw.field("title").value("Tiko @Configuration union");
            jw.field("properties").object();
            for (ConfigurationModel cfg : context.getConfigurations()) {
                writeConfigBlock(jw, cfg);
            }
            jw.endObject();
            // Top-level allows framework-reserved keys (tiko.shutdownTimeout, etc.)
            jw.field("additionalProperties").value(true);
            jw.endObject();
        }
    }

    private void writeConfigBlock(JsonWriter jw, ConfigurationModel cfg) {
        jw.field(cfg.prefix()).object();
        jw.field("type").value("object");
        jw.field("title").value(cfg.qualifiedName());
        jw.field("properties").object();
        for (ConfigFieldModel field : cfg.fields()) {
            jw.field(field.yamlKey()).raw(decorateWithDefault(typeMapper.mapType(field.type()), field));
        }
        jw.endObject();
        jw.field("required").array();
        for (ConfigFieldModel field : cfg.fields()) {
            if (field.cardinality() == ConfigFieldModel.Cardinality.REQUIRED) {
                jw.value(field.fieldName());
            }
        }
        jw.endArray();
        jw.field("additionalProperties").value(false);
        jw.endObject();
    }

    /**
     * Splices the {@code "default": "..."} field into a JSON Schema fragment when
     * the source carries {@code @Default(...)}. Naive string splice — the fragment
     * shapes are tightly controlled by {@link JsonSchemaTypeMapper}.
     */
    private String decorateWithDefault(String fragment, ConfigFieldModel field) {
        if (field.cardinality() != ConfigFieldModel.Cardinality.DEFAULTED || field.defaultValue() == null) {
            return fragment;
        }
        if (!fragment.startsWith("{") || !fragment.endsWith("}")) {
            return fragment;
        }
        String inner = fragment.substring(1, fragment.length() - 1);
        // Defaults for numeric types are passed through unquoted; everything else as a string.
        // Conservative: emit as quoted string — JSON Schema's `default` keyword is just
        // documentation, not validated, so callers reading it can convert.
        String def = "\"default\":\"" + escapeJson(field.defaultValue()) + "\"";
        return "{" + inner + (inner.isEmpty() ? "" : ",") + def + "}";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/topology/ConfigSchemaWriter.java
git commit -m "feat(processor): ConfigSchemaWriter emits JSON Schema draft 2020-12 (#22)"
```

---

## Task 11: Wire `ConfigSchemaWriter` into `generate()`

Same opt-out flag as topology. Emit only when there's at least one `@Configuration`.

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`

- [ ] **Step 1: Add ConfigSchemaWriter call**

After the `TopologyWriter` call from Task 3/6:

```java
if (topologyBundleEnabled() && !context.getConfigurations().isEmpty()) {
    new io.tiko.processor.topology.ConfigSchemaWriter(context).write(processingEnv.getFiler());
}
```

- [ ] **Step 2: Run JsonSchemaTypeMapperTest (now passes — writer is wired)**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=JsonSchemaTypeMapperTest
```

Expected: PASS, all 5 tests green.

- [ ] **Step 3: Run full processor test suite (no regressions)**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
git commit -m "feat(processor): emit META-INF/tiko/config-schema.json (#22)"
```

---

## Task 12: Comprehensive ConfigSchemaWriter IT

End-to-end test covering all cardinalities + types in one fixture.

**Files:**
- Test: `tiko-processor/src/test/java/io/tiko/processor/topology/ConfigSchemaWriterTest.java`

- [ ] **Step 1: Write the IT**

```java
// tiko-processor/src/test/java/io/tiko/processor/topology/ConfigSchemaWriterTest.java
package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class ConfigSchemaWriterTest {

    @Test
    void coversRequiredOptionalDefaultedAcrossSeveralTypes() throws Exception {
        JavaFileObject cfg = JavaFileObjects.forSourceLines(
                "io.example.DbConfig",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "import java.time.Duration;",
                "import java.util.List;",
                "import java.util.Optional;",
                "@Configuration(prefix = \"database\")",
                "public record DbConfig(",
                "  String url,",
                "  String username,",
                "  @Default(\"10\") int poolSize,",
                "  @Default(\"PT30S\") Duration connectTimeout,",
                "  Optional<String> ca,",
                "  List<String> hosts",
                ") {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(cfg);
        assertThat(c).succeeded();

        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json")
                .orElseThrow();
        String json;
        try (var r = new InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }

        // Draft + id
        assertThat(json).contains("\"$schema\": \"https://json-schema.org/draft/2020-12/schema\"");
        assertThat(json).contains("\"$id\": \"tiko://config-schema\"");

        // Per-prefix block
        assertThat(json).contains("\"database\":");
        assertThat(json).contains("\"title\": \"io.example.DbConfig\"");
        assertThat(json).contains("\"additionalProperties\": false");

        // Fields
        assertThat(json).contains("\"url\":").contains("\"type\":\"string\"");
        assertThat(json).contains("\"poolSize\":").contains("\"type\":\"integer\"").contains("\"default\":\"10\"");
        assertThat(json).contains("\"connectTimeout\":").contains("\"format\":\"duration\"");
        assertThat(json).contains("\"hosts\":").contains("\"type\":\"array\"");

        // required[] lists url + username (REQUIRED), excludes ca (OPTIONAL) and defaulted fields
        assertThat(json).contains("\"required\":");
        // crude check: required block contains url + username, doesn't contain poolSize
        int requiredStart = json.indexOf("\"required\":");
        int requiredEnd = json.indexOf("]", requiredStart);
        String requiredSlice = json.substring(requiredStart, requiredEnd);
        assertThat(requiredSlice).contains("\"url\"").contains("\"username\"");
        assertThat(requiredSlice).doesNotContain("poolSize");
        assertThat(requiredSlice).doesNotContain("\"ca\"");
    }

    @Test
    void noConfigurationsMeansNoSchemaFile() {
        JavaFileObject plain = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(plain);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json"))
                .isNotPresent();
    }

    @Test
    void optOutSuppressesConfigSchemaJson() {
        JavaFileObject cfg = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "@Configuration(prefix = \"x\") public record X(String s) {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.topology.bundle=false")
                .compile(cfg);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json"))
                .isNotPresent();
    }
}
```

- [ ] **Step 2: Run the IT**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-processor test -Dtest=ConfigSchemaWriterTest
```

Expected: PASS, 3 tests green.

- [ ] **Step 3: Commit**

```bash
git add tiko-processor/src/test/java/io/tiko/processor/topology/ConfigSchemaWriterTest.java
git commit -m "test(processor): ConfigSchemaWriter IT covering cardinalities + opt-out (#22)"
```

---

## Task 13: PR 2 wrap-up — open the PR

- [ ] **Step 1: Run full build + tests**

```bash
W:\tools\apache-maven\bin\mvn.cmd clean install -pl tiko-processor -am
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Push and open PR**

```bash
git push -u origin feature/22-topology-mcp-pr2
```

```bash
"C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(processor): emit META-INF/tiko/config-schema.json (#22, 2/3)" --body "$(cat <<'EOF'
## Summary

- Annotation processor now emits `META-INF/tiko/config-schema.json` — proper JSON Schema draft 2020-12 covering every `@Configuration` record
- IntelliJ IDEA users can wire this file into YAML autocomplete for typed `config.yaml` editing
- New `JsonSchemaTypeMapper` keeps the type mapping in lockstep with `ConfigSupportedTypes`
- Same opt-out (`-Atiko.topology.bundle=false`) suppresses emission

Second of three PRs against #22. PR 3 ships the `tiko-mcp` server.

## Test plan

- [x] `mvn -pl tiko-processor test` — all green
- [x] New ITs: `ConfigSchemaWriterTest` (cardinalities + opt-out), `JsonSchemaTypeMapperTest` (per-type behaviour)
EOF
)"
```

Wait for user merge. Then start PR 3.

---

# PR 3 — `tiko-mcp` module + example

## Task 14: Scaffold `tiko-mcp` module

Look up the latest stable version of `io.modelcontextprotocol.sdk:mcp` on Maven Central (per CLAUDE.md "Adding Dependencies" — don't copy from blog posts). Pin it in `tiko-bom` and the root `<dependencyManagement>`.

**Files:**
- Create: `tiko-mcp/pom.xml`
- Modify: `pom.xml` (root) — add `<module>tiko-mcp</module>`
- Modify: `tiko-bom/pom.xml` — pin MCP SDK + `tiko-mcp`

- [ ] **Step 1: Look up the latest MCP Java SDK version**

```bash
# Check Maven Central for the latest stable. As of writing, the SDK GA artifacts
# live under groupId io.modelcontextprotocol.sdk. Pick the latest non-SNAPSHOT.
curl -s "https://search.maven.org/solrsearch/select?q=g:%22io.modelcontextprotocol.sdk%22+AND+a:%22mcp%22&core=gav&rows=5&wt=json" | python -m json.tool
```

Record the version in a variable for the next steps; for the plan, refer to it as `<MCP_SDK_VERSION>` — replace with the literal version when implementing.

- [ ] **Step 2: Add tiko-mcp to root pom**

In `pom.xml`, add to `<modules>` (alphabetical-ish, after `tiko-kafka-it`):

```xml
        <module>tiko-mcp</module>
```

In `pom.xml` `<properties>`, add:

```xml
        <mcp-sdk.version>REPLACE_WITH_LOOKED_UP_VERSION</mcp-sdk.version>
```

In `pom.xml` `<dependencyManagement>`, add the internal module + the SDK:

```xml
            <dependency>
                <groupId>io.tiko</groupId>
                <artifactId>tiko-mcp</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp</artifactId>
                <version>${mcp-sdk.version}</version>
            </dependency>
```

- [ ] **Step 3: Mirror in tiko-bom**

In `tiko-bom/pom.xml` `<properties>`, add:

```xml
        <mcp-sdk.version>REPLACE_WITH_LOOKED_UP_VERSION</mcp-sdk.version>
```

In `<dependencyManagement>`, add (under the existing Tiko modules):

```xml
            <dependency>
                <groupId>io.tiko</groupId>
                <artifactId>tiko-mcp</artifactId>
                <version>${tiko.version}</version>
            </dependency>
            <dependency>
                <groupId>io.modelcontextprotocol.sdk</groupId>
                <artifactId>mcp</artifactId>
                <version>${mcp-sdk.version}</version>
            </dependency>
```

- [ ] **Step 4: Create tiko-mcp/pom.xml**

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

    <artifactId>tiko-mcp</artifactId>
    <name>Tiko MCP Server</name>
    <description>Stdio MCP server exposing Tiko's compile-time topology to AI coding agents</description>

    <dependencies>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp</artifactId>
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
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                            <transformers>
                                <transformer
                                    implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.tiko.mcp.TikoMcpServer</mainClass>
                                </transformer>
                                <transformer
                                    implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 5: Stub TikoMcpServer so `mvn compile` succeeds**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java
package io.tiko.mcp;

public final class TikoMcpServer {
    private TikoMcpServer() {}

    public static void main(String[] args) {
        throw new UnsupportedOperationException("Stub — wired in subsequent tasks");
    }
}
```

- [ ] **Step 6: Verify the module compiles**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add pom.xml tiko-bom/pom.xml tiko-mcp/pom.xml tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java
git commit -m "feat(mcp): scaffold tiko-mcp module with MCP Java SDK (#22)"
```

---

## Task 15: `JsonReader` — minimal stdlib JSON parser

Pure Java, no deps. Reads UTF-8 text into `Object` (Map / List / String / Long / Double / Boolean / null). Enough for our two well-known shapes.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/JsonReader.java`
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/JsonReaderTest.java`

- [ ] **Step 1: Write the reader tests**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/JsonReaderTest.java
package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonReaderTest {

    @Test
    void parsesPrimitives() {
        assertThat(JsonReader.parse("\"hello\"")).isEqualTo("hello");
        assertThat(JsonReader.parse("42")).isEqualTo(42L);
        assertThat(JsonReader.parse("3.14")).isEqualTo(3.14);
        assertThat(JsonReader.parse("true")).isEqualTo(true);
        assertThat(JsonReader.parse("false")).isEqualTo(false);
        assertThat(JsonReader.parse("null")).isNull();
    }

    @Test
    void parsesEscapeSequences() {
        assertThat(JsonReader.parse("\"a\\\"b\\\\c\\nd\\te\"")).isEqualTo("a\"b\\c\nd\te");
        assertThat(JsonReader.parse("\"\\u0041\"")).isEqualTo("A");
    }

    @Test
    void parsesArray() {
        Object v = JsonReader.parse("[1, \"two\", true, null]");
        assertThat(v).isInstanceOf(List.class);
        List<?> list = (List<?>) v;
        assertThat(list).containsExactly(1L, "two", true, null);
    }

    @Test
    void parsesObject() {
        Object v = JsonReader.parse("{\"name\": \"Tiko\", \"version\": 1, \"active\": true}");
        assertThat(v).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) v;
        assertThat(map).containsEntry("name", "Tiko").containsEntry("version", 1L).containsEntry("active", true);
    }

    @Test
    void parsesNested() {
        Object v = JsonReader.parse("{\"items\":[{\"k\":\"v\"}]}");
        Map<?, ?> map = (Map<?, ?>) v;
        List<?> items = (List<?>) map.get("items");
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertThat(first).containsEntry("k", "v");
    }

    @Test
    void parsesPrettyPrintedTopologyShape() {
        String json = """
                {
                  "schemaVersion": 1,
                  "components": [
                    {"qualifiedName": "io.example.X", "scope": "SINGLETON"}
                  ]
                }
                """;
        Map<?, ?> m = (Map<?, ?>) JsonReader.parse(json);
        assertThat(m.get("schemaVersion")).isEqualTo(1L);
        List<?> components = (List<?>) m.get("components");
        Map<?, ?> first = (Map<?, ?>) components.get(0);
        assertThat(first.get("qualifiedName")).isEqualTo("io.example.X");
    }

    @Test
    void rejectsTrailingGarbage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JsonReader.parse("42 stuff"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=JsonReaderTest
```

Expected: FAIL — JsonReader doesn't exist.

- [ ] **Step 3: Implement JsonReader**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/JsonReader.java
package io.tiko.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal stdlib-only JSON parser. Returns nested {@code Map<String, Object>},
 * {@code List<Object>}, {@code String}, {@code Long}, {@code Double},
 * {@code Boolean}, or {@code null}. Sufficient for reading the two well-known
 * Tiko build artifacts (topology.json + config-schema.json).
 */
public final class JsonReader {

    private final String src;
    private int pos;

    private JsonReader(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String src) {
        var r = new JsonReader(src);
        r.skipWhitespace();
        Object v = r.readValue();
        r.skipWhitespace();
        if (r.pos != r.src.length()) {
            throw new IllegalArgumentException("Unexpected trailing content at offset " + r.pos);
        }
        return v;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new IllegalArgumentException("Unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        var map = new LinkedHashMap<String, Object>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWhitespace();
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new IllegalArgumentException("Expected , or } at offset " + pos);
        }
    }

    private List<Object> readArray() {
        expect('[');
        var list = new ArrayList<Object>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            Object value = readValue();
            list.add(value);
            skipWhitespace();
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new IllegalArgumentException("Expected , or ] at offset " + pos);
        }
    }

    private String readString() {
        expect('"');
        var sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw new IllegalArgumentException("Unterminated escape");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw new IllegalArgumentException("Bad \\u escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Boolean readBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected true/false at offset " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Expected null at offset " + pos);
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && isNumberChar(src.charAt(pos))) pos++;
        String num = src.substring(start, pos);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at offset " + pos);
        }
        pos++;
    }

    private char peek() {
        skipWhitespace();
        return pos < src.length() ? src.charAt(pos) : '\0';
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=JsonReaderTest
```

Expected: PASS, 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/JsonReader.java tiko-mcp/src/test/java/io/tiko/mcp/JsonReaderTest.java
git commit -m "feat(mcp): minimal stdlib JsonReader (#22)"
```

---

## Task 16: `TopologyStore` — load + merge multi-module artifacts

Walks `<projectDir>/**/target/classes/META-INF/tiko/{topology,config-schema}.json`. Builds in-memory model. Provides query methods used by the four tools.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java`
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/TopologyStoreTest.java`

- [ ] **Step 1: Write the test**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/TopologyStoreTest.java
package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopologyStoreTest {

    @Test
    void mergesTopologyAcrossModules(@TempDir Path root) throws Exception {
        writeJson(root.resolve("module-a/target/classes/META-INF/tiko/topology.json"), """
                {"schemaVersion": 1, "module": "io.tiko.generated.ContainerA",
                 "components": [{"qualifiedName": "io.example.A", "scope": "SINGLETON", "interfaces": []}],
                 "factoryMethods": [], "eventHandlers": [], "eventTriggers": [], "configurations": []}
                """);
        writeJson(root.resolve("module-b/target/classes/META-INF/tiko/topology.json"), """
                {"schemaVersion": 1, "module": "io.tiko.generated.ContainerB",
                 "components": [{"qualifiedName": "io.example.B", "scope": "REQUEST", "interfaces": []}],
                 "factoryMethods": [], "eventHandlers": [], "eventTriggers": [], "configurations": []}
                """);

        var store = TopologyStore.loadFrom(root);
        assertThat(store.components()).hasSize(2);
        assertThat(store.components()).extracting("qualifiedName")
                .containsExactlyInAnyOrder("io.example.A", "io.example.B");
    }

    @Test
    void emptyProjectGivesEmptyStore(@TempDir Path root) {
        var store = TopologyStore.loadFrom(root);
        assertThat(store.components()).isEmpty();
        assertThat(store.configSchema()).isNull();
    }

    @Test
    void loadsConfigSchemaWhenPresent(@TempDir Path root) throws Exception {
        writeJson(root.resolve("m/target/classes/META-INF/tiko/config-schema.json"), """
                {"$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {"database": {"type": "object", "properties": {}}}}
                """);
        var store = TopologyStore.loadFrom(root);
        assertThat(store.configSchema()).isNotNull();
        assertThat(store.configSchemaPrefixes()).containsExactly("database");
    }

    private static void writeJson(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=TopologyStoreTest
```

Expected: FAIL.

- [ ] **Step 3: Implement TopologyStore**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java
package io.tiko.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code META-INF/tiko/topology.json} and {@code META-INF/tiko/config-schema.json}
 * from every Maven module under the given project root and merges the topology
 * documents into a single in-memory model. Config schemas are merged into a
 * single {@code properties: {}} union keyed by prefix (later modules win on
 * collision — the warning surface lives in the loader log).
 */
public final class TopologyStore {

    private final List<Map<String, Object>> components;
    private final List<Map<String, Object>> factoryMethods;
    private final List<Map<String, Object>> eventHandlers;
    private final List<Map<String, Object>> eventTriggers;
    private final List<Map<String, Object>> configurations;
    private final Map<String, Object> configSchema; // nullable

    private TopologyStore(
            List<Map<String, Object>> components,
            List<Map<String, Object>> factoryMethods,
            List<Map<String, Object>> eventHandlers,
            List<Map<String, Object>> eventTriggers,
            List<Map<String, Object>> configurations,
            Map<String, Object> configSchema) {
        this.components = components;
        this.factoryMethods = factoryMethods;
        this.eventHandlers = eventHandlers;
        this.eventTriggers = eventTriggers;
        this.configurations = configurations;
        this.configSchema = configSchema;
    }

    public static TopologyStore loadFrom(Path projectRoot) {
        var components = new ArrayList<Map<String, Object>>();
        var factoryMethods = new ArrayList<Map<String, Object>>();
        var eventHandlers = new ArrayList<Map<String, Object>>();
        var eventTriggers = new ArrayList<Map<String, Object>>();
        var configurations = new ArrayList<Map<String, Object>>();
        Map<String, Object> mergedSchema = null;

        for (Path topologyFile : findFiles(projectRoot, "topology.json")) {
            Map<String, Object> doc = readJsonObject(topologyFile);
            appendIfArray(doc, "components", components);
            appendIfArray(doc, "factoryMethods", factoryMethods);
            appendIfArray(doc, "eventHandlers", eventHandlers);
            appendIfArray(doc, "eventTriggers", eventTriggers);
            appendIfArray(doc, "configurations", configurations);
        }

        for (Path schemaFile : findFiles(projectRoot, "config-schema.json")) {
            Map<String, Object> doc = readJsonObject(schemaFile);
            if (mergedSchema == null) {
                mergedSchema = new LinkedHashMap<>(doc);
                Object props = mergedSchema.get("properties");
                if (props instanceof Map) {
                    mergedSchema.put("properties", new LinkedHashMap<>((Map<?, ?>) props));
                }
            } else {
                Object props = doc.get("properties");
                Object existing = mergedSchema.get("properties");
                if (props instanceof Map && existing instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mergedProps = (Map<String, Object>) existing;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> incoming = (Map<String, Object>) props;
                    mergedProps.putAll(incoming);
                }
            }
        }

        return new TopologyStore(components, factoryMethods, eventHandlers, eventTriggers, configurations, mergedSchema);
    }

    public List<Map<String, Object>> components() {
        return components;
    }

    public List<Map<String, Object>> factoryMethods() {
        return factoryMethods;
    }

    public List<Map<String, Object>> eventHandlers() {
        return eventHandlers;
    }

    public List<Map<String, Object>> eventTriggers() {
        return eventTriggers;
    }

    public List<Map<String, Object>> configurations() {
        return configurations;
    }

    public Map<String, Object> configSchema() {
        return configSchema;
    }

    public List<String> configSchemaPrefixes() {
        if (configSchema == null) return List.of();
        Object props = configSchema.get("properties");
        if (props instanceof Map<?, ?> m) {
            return m.keySet().stream().map(Object::toString).toList();
        }
        return List.of();
    }

    // ----- helpers -----

    private static List<Path> findFiles(Path root, String fileName) {
        var result = new ArrayList<Path>();
        if (!Files.isDirectory(root)) return result;
        PathMatcher matcher = root.getFileSystem()
                .getPathMatcher("glob:**/target/classes/META-INF/tiko/" + fileName);
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file)) {
                        result.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(Path path) {
        try {
            String src = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = JsonReader.parse(src);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException("Expected JSON object in " + path);
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendIfArray(Map<String, Object> doc, String key, List<Map<String, Object>> dest) {
        Object v = doc.get(key);
        if (v instanceof List) {
            for (Object item : (List<Object>) v) {
                if (item instanceof Map) {
                    dest.add((Map<String, Object>) item);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=TopologyStoreTest
```

Expected: PASS, 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java tiko-mcp/src/test/java/io/tiko/mcp/TopologyStoreTest.java
git commit -m "feat(mcp): TopologyStore loads + merges multi-module artifacts (#22)"
```

---

## Task 17: `list_components` tool

Pure function over `TopologyStore.components()`. Returns the filtered slice as JSON.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java`
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListComponentsToolTest.java`

- [ ] **Step 1: Write the test**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/tools/ListComponentsToolTest.java
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

class ListComponentsToolTest {

    @Test
    void noFilterReturnsAll(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":["io.example.IB"]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) tool.execute(Map.of()).get("components");
        assertThat(result).hasSize(2);
    }

    @Test
    void filterByScope(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result =
                (List<Map<String, Object>>) tool.execute(Map.of("scope", "REQUEST")).get("components");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("qualifiedName")).isEqualTo("io.example.B");
    }

    @Test
    void filterByInterface(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":["io.example.Marker"]},
                   {"qualifiedName":"io.example.B","scope":"SINGLETON","interfaces":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result =
                (List<Map<String, Object>>) tool.execute(Map.of("interface", "io.example.Marker")).get("components");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("qualifiedName")).isEqualTo("io.example.A");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=ListComponentsToolTest
```

Expected: FAIL.

- [ ] **Step 3: Implement ListComponentsTool**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java
package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: list every {@code @Component} in the loaded topology, optionally
 * filtered by {@code scope} and/or implemented {@code interface}.
 */
public final class ListComponentsTool {

    public static final String NAME = "list_components";

    private final TopologyStore store;

    public ListComponentsTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        String scope = strOrNull(args.get("scope"));
        String iface = strOrNull(args.get("interface"));

        var filtered = store.components().stream()
                .filter(c -> scope == null || scope.equals(c.get("scope")))
                .filter(c -> iface == null || interfacesContain(c, iface))
                .toList();

        var out = new LinkedHashMap<String, Object>();
        out.put("components", filtered);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean interfacesContain(Map<String, Object> component, String fqn) {
        Object v = component.get("interfaces");
        if (!(v instanceof List)) return false;
        return ((List<Object>) v).stream().anyMatch(fqn::equals);
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=ListComponentsToolTest
```

Expected: PASS, 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/tools/ListComponentsTool.java tiko-mcp/src/test/java/io/tiko/mcp/tools/ListComponentsToolTest.java
git commit -m "feat(mcp): list_components tool (#22)"
```

---

## Task 18: `list_events` tool

Cross-references `eventHandlers[]` with `eventTriggers[]`.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ListEventsTool.java`
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ListEventsToolTest.java`

- [ ] **Step 1: Write the test**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/tools/ListEventsToolTest.java
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

class ListEventsToolTest {

    @Test
    void groupsHandlersAndPublishersByEventType(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[], "factoryMethods":[], "configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"io.example.N","methodName":"on","eventType":"io.example.OrderPlaced","async":false}
                 ],
                 "eventTriggers":[
                   {"handlerClass":"io.example.OrderService","handlerMethod":"validate",
                    "eventName":"io.example.OrderPlaced","async":false,"spread":false,"guards":[]}
                 ]}
                """);
        var tool = new ListEventsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) tool.execute(Map.of()).get("events");
        assertThat(events).hasSize(1);
        Map<String, Object> e = events.get(0);
        assertThat(e.get("eventType")).isEqualTo("io.example.OrderPlaced");
        assertThat((List<?>) e.get("publishers")).hasSize(1);
        assertThat((List<?>) e.get("handlers")).hasSize(1);
    }

    @Test
    void filterByEventType(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[], "factoryMethods":[], "configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"N","methodName":"a","eventType":"io.example.A","async":false},
                   {"declaringClass":"N","methodName":"b","eventType":"io.example.B","async":false}
                 ],
                 "eventTriggers":[]}
                """);
        var tool = new ListEventsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                (List<Map<String, Object>>) tool.execute(Map.of("eventType", "io.example.A")).get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType")).isEqualTo("io.example.A");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=ListEventsToolTest
```

Expected: FAIL.

- [ ] **Step 3: Implement ListEventsTool**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/tools/ListEventsTool.java
package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: list every event type carrying at least one {@code @EventHandler}
 * or {@code @EventTrigger}. Each entry lists handlers (subscribers) and publishers
 * (declarative triggers). Programmatic {@code EventBus.publish(...)} calls are
 * invisible to the processor and not included.
 */
public final class ListEventsTool {

    public static final String NAME = "list_events";

    private final TopologyStore store;

    public ListEventsTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        String filter = args.get("eventType") == null ? null : args.get("eventType").toString();

        var eventTypes = new LinkedHashSet<String>();
        for (var h : store.eventHandlers()) {
            String t = (String) h.get("eventType");
            if (t != null) eventTypes.add(t);
        }
        for (var t : store.eventTriggers()) {
            String name = (String) t.get("eventName");
            if (name != null) eventTypes.add(name);
        }

        var events = new ArrayList<Map<String, Object>>();
        for (String eventType : eventTypes) {
            if (filter != null && !filter.equals(eventType)) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("eventType", eventType);
            entry.put("publishers", store.eventTriggers().stream()
                    .filter(t -> eventType.equals(t.get("eventName")))
                    .map(t -> {
                        var p = new LinkedHashMap<String, Object>();
                        p.put("class", t.get("handlerClass"));
                        p.put("method", t.get("handlerMethod"));
                        p.put("eventName", t.get("eventName"));
                        p.put("async", t.get("async"));
                        return (Map<String, Object>) p;
                    })
                    .toList());
            entry.put("handlers", store.eventHandlers().stream()
                    .filter(h -> eventType.equals(h.get("eventType")))
                    .map(h -> {
                        var p = new LinkedHashMap<String, Object>();
                        p.put("class", h.get("declaringClass"));
                        p.put("method", h.get("methodName"));
                        p.put("async", h.get("async"));
                        return (Map<String, Object>) p;
                    })
                    .toList());
            events.add(entry);
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("events", events);
        return out;
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=ListEventsToolTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/tools/ListEventsTool.java tiko-mcp/src/test/java/io/tiko/mcp/tools/ListEventsToolTest.java
git commit -m "feat(mcp): list_events tool (#22)"
```

---

## Task 19: `get_config_schema` tool

Returns full schema or `properties.<prefix>` slice.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/GetConfigSchemaTool.java`
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/tools/GetConfigSchemaToolTest.java`

- [ ] **Step 1: Write the test**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/tools/GetConfigSchemaToolTest.java
package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GetConfigSchemaToolTest {

    @Test
    void noPrefixReturnsFullSchema(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "properties":{"database":{"type":"object","properties":{}}}}
                """);
        var tool = new GetConfigSchemaTool(store);

        Object schema = tool.execute(Map.of()).get("schema");
        assertThat(schema).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) schema).get("$schema"))
                .isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    void prefixReturnsSlice(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "properties":{
                    "database":{"type":"object","title":"DbCfg"},
                    "cache":{"type":"object","title":"CacheCfg"}
                 }}
                """);
        var tool = new GetConfigSchemaTool(store);

        Map<?, ?> slice = (Map<?, ?>) tool.execute(Map.of("prefix", "cache")).get("schema");
        assertThat(slice.get("title")).isEqualTo("CacheCfg");
    }

    @Test
    void unknownPrefixThrows(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"$schema":"https://json-schema.org/draft/2020-12/schema",
                 "type":"object",
                 "properties":{"database":{"type":"object"}}}
                """);
        var tool = new GetConfigSchemaTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("prefix", "nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("database");
    }

    private TopologyStore storeWith(Path root, String schemaJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/config-schema.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, schemaJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=GetConfigSchemaToolTest
```

Expected: FAIL.

- [ ] **Step 3: Implement GetConfigSchemaTool**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/tools/GetConfigSchemaTool.java
package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool: returns the union JSON Schema for every {@code @Configuration} record
 * discovered in the project. With a {@code prefix} argument, returns just the
 * slice at {@code properties.<prefix>}.
 */
public final class GetConfigSchemaTool {

    public static final String NAME = "get_config_schema";

    private final TopologyStore store;

    public GetConfigSchemaTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        Object prefixObj = args.get("prefix");
        String prefix = prefixObj == null ? null : prefixObj.toString();

        Map<String, Object> schema = store.configSchema();
        var out = new LinkedHashMap<String, Object>();

        if (prefix == null || prefix.isEmpty()) {
            out.put("schema", schema);
            return out;
        }

        if (schema == null) {
            throw new IllegalArgumentException("No config schema is present in this project");
        }

        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> propsMap) || !propsMap.containsKey(prefix)) {
            throw new IllegalArgumentException("Unknown config prefix '" + prefix + "'. Known: " + store.configSchemaPrefixes());
        }

        out.put("schema", propsMap.get(prefix));
        return out;
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=GetConfigSchemaToolTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/tools/GetConfigSchemaTool.java tiko-mcp/src/test/java/io/tiko/mcp/tools/GetConfigSchemaToolTest.java
git commit -m "feat(mcp): get_config_schema tool (#22)"
```

---

## Task 20: `explain_wiring` tool

BFS over `constructorDependencies` for a given root component. Marks cycles + cross-scope proxies.

**Files:**
- Create: `tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java`
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/tools/ExplainWiringToolTest.java`

- [ ] **Step 1: Write the test**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/tools/ExplainWiringToolTest.java
package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplainWiringToolTest {

    @Test
    void walksTransitiveDependencies(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.B","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                   {"qualifiedName":"io.example.B","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        Map<String, Object> result = tool.execute(Map.of("componentFqn", "io.example.A"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>) result.get("tree");
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("depth")).isEqualTo(0L);
        assertThat(tree.get(1).get("depth")).isEqualTo(1L);
    }

    @Test
    void flagsCycles(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.B","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                   {"qualifiedName":"io.example.B","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.A","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree =
                (List<Map<String, Object>>) tool.execute(Map.of("componentFqn", "io.example.A")).get("tree");
        // A → B → A (cycle flagged on the re-visit)
        assertThat(tree.stream().anyMatch(n -> Boolean.TRUE.equals(n.get("cycle")))).isTrue();
    }

    @Test
    void unknownComponentThrowsWithSuggestions(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.OrderService","scope":"SINGLETON","interfaces":[],"constructorDependencies":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("componentFqn", "OrderService")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("io.example.OrderService");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
```

- [ ] **Step 2: Verify tests fail**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=ExplainWiringToolTest
```

Expected: FAIL.

- [ ] **Step 3: Implement ExplainWiringTool**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java
package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool: BFS over a component's constructor-dependency edges, marking cycles
 * (re-visits) and cross-scope proxy edges.
 */
public final class ExplainWiringTool {

    public static final String NAME = "explain_wiring";

    private static final long DEFAULT_MAX_DEPTH = 10L;

    private final TopologyStore store;

    public ExplainWiringTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        String fqn = required(args, "componentFqn");
        long maxDepth = args.get("maxDepth") instanceof Long l ? l : DEFAULT_MAX_DEPTH;

        Map<String, Object> root = findComponent(fqn);
        if (root == null) {
            var matches = store.components().stream()
                    .map(c -> (String) c.get("qualifiedName"))
                    .filter(n -> n != null && n.contains(simpleName(fqn)))
                    .toList();
            throw new IllegalArgumentException(
                    "Unknown component '" + fqn + "'. Did you mean one of: " + matches + "?");
        }

        var tree = new ArrayList<Map<String, Object>>();
        var queue = new ArrayDeque<Node>();
        var visited = new HashSet<String>();
        queue.add(new Node(fqn, 0L, null, false));

        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n.depth > maxDepth) continue;

            Map<String, Object> component = findComponent(n.fqn);
            if (component == null) continue;

            var entry = new LinkedHashMap<String, Object>();
            entry.put("depth", n.depth);
            entry.put("component", component);
            entry.put("via", n.via);
            boolean isCycle = !visited.add(n.fqn);
            entry.put("cycle", isCycle);
            entry.put("proxied", Boolean.TRUE.equals(component.get("requiresProxy")));
            tree.add(entry);

            if (isCycle) continue; // don't descend through cycles

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> deps = (List<Map<String, Object>>) component.getOrDefault("constructorDependencies", List.of());
            for (Map<String, Object> dep : deps) {
                String depType = (String) dep.get("type");
                if (depType != null) {
                    queue.add(new Node(depType, n.depth + 1, dep, false));
                }
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("root", root);
        out.put("tree", tree);
        return out;
    }

    private Map<String, Object> findComponent(String fqn) {
        return store.components().stream()
                .filter(c -> fqn.equals(c.get("qualifiedName")))
                .findFirst()
                .orElse(null);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String required(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.toString();
    }

    private record Node(String fqn, long depth, Map<String, Object> via, boolean dummy) {}
}
```

- [ ] **Step 4: Run tests to verify pass**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=ExplainWiringToolTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/tools/ExplainWiringTool.java tiko-mcp/src/test/java/io/tiko/mcp/tools/ExplainWiringToolTest.java
git commit -m "feat(mcp): explain_wiring tool with cycle + proxy detection (#22)"
```

---

## Task 21: `TikoMcpServer` — wire tools into the MCP stdio loop

Use the official `io.modelcontextprotocol.sdk:mcp` SDK. Register the four tools with their input schemas, route invocations to the `*Tool.execute(...)` methods, and serve over stdio.

Implementation detail: the MCP Java SDK exposes a `McpServer` builder where tools are registered with name + input JSON schema + handler. The exact API symbols depend on the SDK version pinned in Task 14 — consult [`io.modelcontextprotocol.sdk:mcp`](https://github.com/modelcontextprotocol/java-sdk) Javadoc / README for the version you pinned. The skeleton below assumes the typical SDK shape; rename method calls if needed.

**Files:**
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java`

- [ ] **Step 1: Implement TikoMcpServer**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java
package io.tiko.mcp;

import io.tiko.mcp.tools.ExplainWiringTool;
import io.tiko.mcp.tools.GetConfigSchemaTool;
import io.tiko.mcp.tools.ListComponentsTool;
import io.tiko.mcp.tools.ListEventsTool;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entrypoint for the {@code tiko-mcp} runnable jar. Reads {@code args[0]} as the
 * project root, walks the multi-module classpath layout for
 * {@code META-INF/tiko/topology.json} and {@code config-schema.json}, then serves
 * the four read-only MCP tools over stdio.
 *
 * <p>Stdout is reserved for JSON-RPC framing — all logging goes to stderr via
 * {@link java.lang.System.Logger}.
 */
public final class TikoMcpServer {

    private static final class LoggerHolder {
        static final Logger LOG = System.getLogger("io.tiko.mcp");
    }

    private TikoMcpServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java -jar tiko-mcp.jar <project-dir>");
            System.exit(2);
        }
        Path projectRoot = Paths.get(args[0]).toAbsolutePath();
        LoggerHolder.LOG.log(Level.INFO, "Loading topology from {0}", projectRoot);

        TopologyStore store = TopologyStore.loadFrom(projectRoot);
        LoggerHolder.LOG.log(Level.INFO, "Loaded {0} components, {1} configurations",
                store.components().size(), store.configurations().size());

        var listComponents = new ListComponentsTool(store);
        var listEvents = new ListEventsTool(store);
        var getConfigSchema = new GetConfigSchemaTool(store);
        var explainWiring = new ExplainWiringTool(store);

        // ==========================================================================
        // SDK wiring — replace these method calls to match the pinned MCP SDK API.
        // The shape below is the typical pattern: build a server, register each tool
        // with a name + input JSON schema + handler lambda, then start the stdio loop.
        //
        // Example (pseudo-API; adjust to the real SDK):
        //
        //   var server = McpServer.builder()
        //       .name("tiko-mcp")
        //       .version("0.1.0")
        //       .tool(McpTool.builder()
        //           .name(ListComponentsTool.NAME)
        //           .description("List @Component classes (optionally filter by scope or interface).")
        //           .inputSchema("""
        //               {"type":"object","properties":{
        //                  "scope":{"type":"string","enum":["SINGLETON","REQUEST","EVENT","PROTOTYPE"]},
        //                  "interface":{"type":"string"}}}""")
        //           .handler(args -> listComponents.execute(args))
        //           .build())
        //       .tool(/* list_events */)
        //       .tool(/* get_config_schema */)
        //       .tool(/* explain_wiring */)
        //       .build();
        //   server.serveStdio();
        //
        // Consult the README of the SDK version pinned in tiko-bom for the actual
        // class names and method signatures.
        // ==========================================================================

        new McpStdioBridge(listComponents, listEvents, getConfigSchema, explainWiring).run();
    }
}
```

- [ ] **Step 2: Create `McpStdioBridge` as the SDK-glue class**

```java
// tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java
package io.tiko.mcp;

import io.tiko.mcp.tools.ExplainWiringTool;
import io.tiko.mcp.tools.GetConfigSchemaTool;
import io.tiko.mcp.tools.ListComponentsTool;
import io.tiko.mcp.tools.ListEventsTool;

/**
 * Thin adapter from Tiko's tool classes to the MCP SDK. Kept in a separate class
 * so {@link TikoMcpServer#main(String[])} stays readable and the SDK touch points
 * live in one place.
 *
 * <p><strong>Implementation note:</strong> the SDK class names and method
 * signatures depend on the version pinned in {@code tiko-bom}. Implement
 * {@link #run()} using the SDK's builder/server API — register each of the four
 * tool names with its input JSON schema and route the handler to the matching
 * {@code *.execute(args)} method below.
 */
public final class McpStdioBridge {

    private final ListComponentsTool listComponents;
    private final ListEventsTool listEvents;
    private final GetConfigSchemaTool getConfigSchema;
    private final ExplainWiringTool explainWiring;

    public McpStdioBridge(
            ListComponentsTool listComponents,
            ListEventsTool listEvents,
            GetConfigSchemaTool getConfigSchema,
            ExplainWiringTool explainWiring) {
        this.listComponents = listComponents;
        this.listEvents = listEvents;
        this.getConfigSchema = getConfigSchema;
        this.explainWiring = explainWiring;
    }

    /**
     * Start the SDK-managed stdio JSON-RPC loop. Returns when stdin closes.
     *
     * <p>Wire the four tools using the MCP SDK's tool-registration API. Each tool's
     * input schema is shown below for the implementer to inline at the SDK call site.
     */
    public void run() throws Exception {
        // list_components input schema
        String listComponentsSchema = """
                {"type":"object","properties":{
                   "scope":{"type":"string","enum":["SINGLETON","REQUEST","EVENT","PROTOTYPE"]},
                   "interface":{"type":"string"}}}""";

        // list_events input schema
        String listEventsSchema = """
                {"type":"object","properties":{
                   "eventType":{"type":"string"}}}""";

        // get_config_schema input schema
        String getConfigSchemaSchema = """
                {"type":"object","properties":{
                   "prefix":{"type":"string"}}}""";

        // explain_wiring input schema
        String explainWiringSchema = """
                {"type":"object","properties":{
                   "componentFqn":{"type":"string"},
                   "maxDepth":{"type":"integer","default":10}},
                 "required":["componentFqn"]}""";

        // TODO during implementation: replace the four pseudo-calls below with the
        // real SDK API. The handler for each tool returns the JSON the SDK should
        // wrap as the tool result content (typically toString on the LinkedHashMap is
        // sufficient if the SDK accepts a JSON-string content type; otherwise the SDK
        // will have its own Content/Result type to wrap with).
        throw new UnsupportedOperationException(
                "Wire the four tools (list_components, list_events, get_config_schema, explain_wiring) "
                        + "to the MCP SDK pinned in tiko-bom. See inline schema strings above.");
    }
}
```

The SDK wiring is deliberately left as a marked TODO in the bridge — the tool logic is fully tested in isolation (Tasks 17-20). When implementing, replace the `throw` with the SDK-specific server setup using the four `*Tool.execute(...)` methods as handlers.

- [ ] **Step 3: Verify compile**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-mcp/src/main/java/io/tiko/mcp/TikoMcpServer.java tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java
git commit -m "feat(mcp): TikoMcpServer entrypoint + McpStdioBridge skeleton (#22)"
```

---

## Task 22: Implement the SDK wiring in `McpStdioBridge.run()`

The implementer (or follow-up) replaces the `throw` with the real SDK calls. This task is intentionally separate so the tool tests above stay independent of the SDK API surface.

**Files:**
- Modify: `tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java`

- [ ] **Step 1: Look up the SDK API in the version you pinned**

Open the README of `io.modelcontextprotocol.sdk:mcp` at the pinned version. Identify:
- Server builder class (e.g. `McpServer`, `McpServerBuilder`, etc.)
- Tool registration API (typically `addTool(name, description, inputSchemaJson, handler)`)
- Handler signature (typically `Function<Map<String,Object>, ?>` or similar)
- Stdio transport (typically `server.serve()` or `StdioTransport.attach(server)`)
- Content-result wrapping (`TextContent`, `ToolResult`, etc.)

- [ ] **Step 2: Replace the `throw` with concrete SDK calls**

Wire each tool's `execute(args)` return value through the SDK's result-content wrapper. Each handler returns the `Map<String, Object>` produced by the tool; serialize it to JSON via a small adapter or the SDK's native object support. If the SDK expects strings, use Jackson's stdlib equivalent — at this stage you can add Jackson as a `tiko-mcp` dep (it's not on the processor path), or hand-roll a writer mirroring `tiko-processor`'s `JsonWriter` (copy it into `tiko-mcp/src/main/java/io/tiko/mcp/JsonOutput.java`).

If the implementer decides Jackson is acceptable here, add it as a dependency in `tiko-mcp/pom.xml`:

```xml
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
```

The processor's no-Jackson constraint does not apply to the runtime MCP server; this is a tools-jar, not an annotation processor.

- [ ] **Step 3: Verify `mvn package` produces a runnable jar**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp -am package
```

Expected: BUILD SUCCESS, `tiko-mcp/target/tiko-mcp-0.1.0.jar` exists with a manifest `Main-Class: io.tiko.mcp.TikoMcpServer`.

- [ ] **Step 4: Smoke test the jar starts**

```bash
java -jar tiko-mcp/target/tiko-mcp-0.1.0.jar --help 2>&1 | head -5
```

Expected: usage message printed to stderr (since stdout is reserved for JSON-RPC framing), exit code 2.

- [ ] **Step 5: Commit**

```bash
git add tiko-mcp/pom.xml tiko-mcp/src/main/java/io/tiko/mcp/McpStdioBridge.java
git commit -m "feat(mcp): wire tools to MCP SDK stdio transport (#22)"
```

---

## Task 23: Subprocess smoke test (`tools/list` over JSON-RPC)

End-to-end: spawn the shaded jar against a fixture project dir, write a JSON-RPC `initialize` + `tools/list` request, parse the response, assert the four tools are advertised.

**Files:**
- Test: `tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java`

- [ ] **Step 1: Write the IT**

```java
// tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java
package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spawns the shaded {@code tiko-mcp.jar} as a subprocess, sends one JSON-RPC
 * {@code tools/list} request over stdin, asserts the response advertises the
 * four tools.
 *
 * <p>Skipped when {@code tiko-mcp/target/tiko-mcp-0.1.0.jar} is not built —
 * keeps {@code mvn test} green on freshly-cloned trees.
 */
class TikoMcpServerSubprocessIT {

    @Test
    void serverAdvertisesFourTools(@TempDir Path projectDir) throws Exception {
        Path jar = Paths.get("target/tiko-mcp-0.1.0.jar");
        if (!Files.exists(jar)) {
            // Skipping: shaded jar not built yet. Run `mvn package` first.
            return;
        }

        // Minimal fixture so TopologyStore.loadFrom() finds at least one component.
        Path topology = projectDir.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(topology.getParent());
        Files.writeString(topology, """
                {"schemaVersion":1, "module":"m",
                 "components":[{"qualifiedName":"io.example.X","scope":"SINGLETON","interfaces":[]}],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """, StandardCharsets.UTF_8);

        var pb = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-jar", jar.toAbsolutePath().toString(),
                projectDir.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        try (var stdin = new PrintWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8), true);
                var stdout = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {

            // 1. initialize
            stdin.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}");

            // 2. tools/list
            stdin.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

            // Read frames until we see the list of tools. Generous timeout for CI.
            String accumulated = "";
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line == null) break;
                    accumulated += line + "\n";
                    if (accumulated.contains("list_components")
                            && accumulated.contains("list_events")
                            && accumulated.contains("get_config_schema")
                            && accumulated.contains("explain_wiring")) {
                        break;
                    }
                } else {
                    Thread.sleep(50);
                }
            }

            assertThat(accumulated)
                    .contains("list_components")
                    .contains("list_events")
                    .contains("get_config_schema")
                    .contains("explain_wiring");
        } finally {
            proc.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
```

- [ ] **Step 2: Build the jar then run the IT**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp -am package
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-mcp test -Dtest=TikoMcpServerSubprocessIT
```

Expected: BUILD SUCCESS. If the test "skips" because the jar wasn't built yet, run `mvn package` first.

- [ ] **Step 3: Commit**

```bash
git add tiko-mcp/src/test/java/io/tiko/mcp/TikoMcpServerSubprocessIT.java
git commit -m "test(mcp): subprocess IT for tools/list (#22)"
```

---

## Task 24: Example `tiko-examples/13_mcp_introspection`

Self-contained Tiko app with components + a `@Configuration` + an `@EventHandler` + an `@EventTrigger`, plus a README transcript showing the four tool calls.

**Files:**
- Create: `tiko-examples/13_mcp_introspection/pom.xml`
- Create: `tiko-examples/13_mcp_introspection/config.yaml`
- Create: `tiko-examples/13_mcp_introspection/.mcp.json`
- Create: `tiko-examples/13_mcp_introspection/README.md`
- Create: `tiko-examples/13_mcp_introspection/src/main/java/example/Main.java`
- Create: `tiko-examples/13_mcp_introspection/src/main/java/example/OrderService.java`
- Create: `tiko-examples/13_mcp_introspection/src/main/java/example/OrderRepository.java`
- Create: `tiko-examples/13_mcp_introspection/src/main/java/example/DbConfig.java`
- Create: `tiko-examples/13_mcp_introspection/src/main/java/example/events/OrderPlaced.java`
- Create: `tiko-examples/13_mcp_introspection/src/main/java/example/events/OrderValidated.java`
- Modify: `tiko-examples/pom.xml` — add `<module>13_mcp_introspection</module>`

- [ ] **Step 1: Add the example to tiko-examples parent**

Open `tiko-examples/pom.xml`, append to `<modules>` (after `12_testing` or whichever is last):

```xml
        <module>13_mcp_introspection</module>
```

- [ ] **Step 2: Create the example pom**

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

    <artifactId>13_mcp_introspection</artifactId>
    <packaging>jar</packaging>
    <name>13 - MCP Introspection Example</name>
    <description>Runnable Tiko app + .mcp.json snippet showing how AI agents query topology via tiko-mcp.</description>

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
            <groupId>io.tiko</groupId>
            <artifactId>tiko-config</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Create the components**

```java
// tiko-examples/13_mcp_introspection/src/main/java/example/Main.java
package example;

import io.tiko.Tiko;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try (var c = Tiko.create()) {
            var orderService = c.get(OrderService.class);
            orderService.place("ord-1", 4200L);
        }
    }
}
```

```java
// tiko-examples/13_mcp_introspection/src/main/java/example/OrderService.java
package example;

import example.events.OrderPlaced;
import example.events.OrderValidated;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.annotations.*;

@Component(scope = Scope.SINGLETON)
public class OrderService {

    private final OrderRepository repo;
    private final EventBus bus;

    @Inject
    public OrderService(OrderRepository repo, EventBus bus) {
        this.repo = repo;
        this.bus = bus;
    }

    public void place(String id, long amountCents) {
        repo.save(id, amountCents);
        bus.publish(new OrderPlaced(id, amountCents));
    }

    @EventHandler
    @EventTrigger(eventName = "OrderValidated")
    public OrderValidated validate(OrderPlaced placed) {
        return new OrderValidated(placed.id(), true);
    }
}
```

```java
// tiko-examples/13_mcp_introspection/src/main/java/example/OrderRepository.java
package example;

import io.tiko.Scope;
import io.tiko.annotations.*;

@Component(scope = Scope.REQUEST)
public class OrderRepository implements Orders {

    private final DbConfig config;

    @Inject
    public OrderRepository(DbConfig config) {
        this.config = config;
    }

    public void save(String id, long amountCents) {
        // Pretend to persist to config.url()
    }
}

interface Orders {}
```

```java
// tiko-examples/13_mcp_introspection/src/main/java/example/DbConfig.java
package example;

import io.tiko.annotations.*;

@Configuration(prefix = "database")
public record DbConfig(String url, String username, @Default("10") int poolSize) {}
```

```java
// tiko-examples/13_mcp_introspection/src/main/java/example/events/OrderPlaced.java
package example.events;

public record OrderPlaced(String id, long amountCents) {}
```

```java
// tiko-examples/13_mcp_introspection/src/main/java/example/events/OrderValidated.java
package example.events;

public record OrderValidated(String id, boolean valid) {}
```

- [ ] **Step 4: Create config.yaml**

```yaml
# tiko-examples/13_mcp_introspection/config.yaml
database:
  url: jdbc:h2:mem:demo
  username: demo
  poolSize: 5
```

- [ ] **Step 5: Create .mcp.json**

```json
{
  "mcpServers": {
    "tiko": {
      "command": "java",
      "args": [
        "-jar",
        "../../tiko-mcp/target/tiko-mcp-0.1.0.jar",
        "."
      ]
    }
  }
}
```

- [ ] **Step 6: Create README.md with transcript**

```markdown
# 13 — MCP introspection

A tiny Tiko app showing how an MCP-aware coding agent (Claude Code,
Cursor, …) can introspect the app's wiring at compile time.

## Setup

1. From the repository root, build everything:

       mvn -pl tiko-mcp,tiko-examples/13_mcp_introspection -am clean install

   This emits `META-INF/tiko/topology.json` + `config-schema.json` into
   `tiko-examples/13_mcp_introspection/target/classes/...` and produces
   the runnable `tiko-mcp/target/tiko-mcp-0.1.0.jar`.

2. Open this directory in your MCP-aware agent. The agent picks up
   `.mcp.json` automatically (Claude Code, Cursor) — or import it manually.

## Sample agent queries

### "List every singleton component"

Tool: `list_components`
Args: `{"scope": "SINGLETON"}`

Returns:

```json
{
  "components": [
    {"qualifiedName": "example.OrderService", "scope": "SINGLETON",
     "interfaces": [], "constructorDependencies": [...] }
  ]
}
```

### "What handlers listen to OrderPlaced?"

Tool: `list_events`
Args: `{"eventType": "example.events.OrderPlaced"}`

Returns:

```json
{
  "events": [
    {"eventType": "example.events.OrderPlaced",
     "publishers": [],
     "handlers": [
       {"class": "example.OrderService", "method": "validate", "async": false}
     ]
    }
  ]
}
```

### "What config keys does this app accept?"

Tool: `get_config_schema`
Args: `{}`

Returns a JSON Schema describing `database.url`, `database.username`,
`database.poolSize` (default 10).

### "What does OrderService depend on?"

Tool: `explain_wiring`
Args: `{"componentFqn": "example.OrderService"}`

Returns a depth-tagged tree: OrderService → OrderRepository → DbConfig.
```

- [ ] **Step 7: Build the example and verify it produces the JSON artifacts**

```bash
W:\tools\apache-maven\bin\mvn.cmd -pl tiko-examples/13_mcp_introspection -am clean compile
ls tiko-examples/13_mcp_introspection/target/classes/META-INF/tiko/
```

Expected: `topology.json` and `config-schema.json` present.

- [ ] **Step 8: Commit**

```bash
git add tiko-examples/pom.xml tiko-examples/13_mcp_introspection/
git commit -m "feat(examples): 13_mcp_introspection — agent transcript demo (#22)"
```

---

## Task 25: README + roadmap updates

Surface the new capability in the docs.

**Files:**
- Modify: `README.md`
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Add the README section**

Find the existing "Scaffold a new project (archetype)" section in `README.md` and add this section immediately after it:

```markdown
### AI-agent topology server (MCP)

Every Tiko build emits machine-readable topology + config schema to
`META-INF/tiko/`. The `tiko-mcp` companion jar exposes them to any
MCP-aware coding agent (Claude Code, Cursor, …):

    java -jar tiko-mcp.jar /path/to/your/project

The metadata ships inside the jar so MCP can also introspect Tiko
dependencies you didn't build yourself. To suppress emission for a
module (closed-source service, sensitive jars), add
`-Atiko.topology.bundle=false` to the annotation processor args.

See [`tiko-examples/13_mcp_introspection`](./tiko-examples/13_mcp_introspection)
for a runnable demo.
```

Then find the "What ships today" bullet list and append:

```markdown
- ✅ Machine-readable topology + config schema + MCP server — every build emits `META-INF/tiko/topology.json` and `META-INF/tiko/config-schema.json`; `tiko-mcp` exposes both to AI agents via stdio MCP. See [topology schema](./docs/topology-schema.md), [`tiko-mcp`](./tiko-mcp), and [`tiko-examples/13_mcp_introspection`](./tiko-examples/13_mcp_introspection). ([#22](https://github.com/tomas-samek/tiko-di/issues/22))
```

- [ ] **Step 2: Update roadmap.md**

In `docs/roadmap.md`, find the Phase 3 section. Update the heading from "5/6 closed" to "6/6 closed". Move the "Open: machine-readable topology …" bullet up into the "Shipped:" list:

```markdown
- ✅ Machine-readable topology + config schema, plus an MCP server so AI agents can introspect the wiring ([#22](https://github.com/tomas-samek/tiko-di/issues/22)).
```

Delete the "Open:" sub-section (Phase 3 is now closed).

Also update the milestone line:

```markdown
[Phase 3 milestone](https://github.com/tomas-samek/tiko-di/milestone/3) — 6/6 closed.
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/roadmap.md
git commit -m "docs: ship machine-readable topology + MCP, close Phase 3 (#22)"
```

---

## Task 26: PR 3 wrap-up — open the final PR for #22

- [ ] **Step 1: Full multi-module build + test**

```bash
W:\tools\apache-maven\bin\mvn.cmd clean install
```

Expected: BUILD SUCCESS across every module including `tiko-mcp` and `tiko-examples/13_mcp_introspection`.

- [ ] **Step 2: Push and open PR**

```bash
git push -u origin feature/22-topology-mcp-pr3
```

```bash
"C:\Program Files\GitHub CLI\gh.exe" pr create --title "feat(mcp): tiko-mcp stdio server + introspection example (#22, 3/3)" --body "$(cat <<'EOF'
## Summary

- New `tiko-mcp` module — stdio MCP server exposing four read-only tools (`list_components`, `list_events`, `get_config_schema`, `explain_wiring`) to AI coding agents
- Multi-module aware: walks `**/target/classes/META-INF/tiko/{topology,config-schema}.json` under the project dir
- Distributed as a runnable shaded jar via maven-shade-plugin
- New `tiko-examples/13_mcp_introspection/` — runnable Tiko app + `.mcp.json` snippet + transcript README showing the four tool calls
- README "AI-agent topology server" section
- Closes #22 — Phase 3 milestone reaches 6/6

Third of three PRs against #22; the previous two land the JSON artifacts the server reads.

## Test plan

- [x] `mvn install` — all modules green
- [x] `JsonReaderTest`, `TopologyStoreTest`, four `*ToolTest`s — unit coverage
- [x] `TikoMcpServerSubprocessIT` — spawn shaded jar, JSON-RPC `tools/list` smoke
- [x] Manually verified `tiko-examples/13_mcp_introspection` emits both JSON artifacts on `mvn compile`
EOF
)"
```

Wait for user merge. After merge: clean up branches.

```bash
git checkout main
git pull
git branch -D feature/22-topology-mcp feature/22-topology-mcp-pr2 feature/22-topology-mcp-pr3
```

---

## Verification — spec coverage checklist

Map every acceptance criterion in the spec to a task that implements it:

| Spec acceptance criterion | Task(s) |
| --- | --- |
| `META-INF/tiko/topology.json` emitted on every compile when there's anything to describe; v1 documented | 2, 3, 4, 7 |
| `META-INF/tiko/config-schema.json` emitted; valid JSON Schema draft 2020-12 | 10, 11, 12 |
| `tiko-mcp` runnable jar passes MCP protocol smoke (`tools/list` returns four tools) | 22, 23 |
| `tiko-examples/13_mcp_introspection` runs end-to-end; README transcript | 24 |
| README "AI-agent topology server" section | 25 |
| Roadmap reflects #22 shipped; Phase 3 milestone closed | 25 |
| `-Atiko.topology.bundle=false` suppresses emission of both files | 6, 12 |
