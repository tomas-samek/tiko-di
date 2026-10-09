# Security Model

The registry `tiko-security` checks pull requests, release deltas and audits against. Each
entry names an attack surface in Tiko's own code or supply chain, the threat, the rule that
guards it, where the rule is enforced, and its current status. It covers Tiko itself — not
the security of applications built on it (authentication, authorization and the like are
plug-ins). Disclosure policy lives in [`SECURITY.md`](../SECURITY.md).

Facts verified against the code on 2026-10-07 (periodic audit). Each anchor names a file and a symbol; line
numbers are a convenience and drift.

---

### SEC-1 — YAML configuration loads data only

**Surface.** `tiko-config` reading `application.yaml` and profile overlays.

**Threat.** A YAML document with global tags (`!!java.…`, `!!javax.…`) instantiating
arbitrary classes during load.

**Rule.** YAML is read as data only: maps, lists and scalars. No tag may select a Java class.

**Enforced by.** Code: `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java`.
It parses with `yaml.compose(...)` and walks the node tree itself, so no SnakeYAML constructor
runs. `new Yaml(new SafeConstructor(opts))` is a second line of defence that `compose` never
reaches. Global tags (`!!java.…`) are rejected at compose time by the default `LoaderOptions`
tag inspector, as a `ConfigValidationException` ("Global tag is not allowed"). No regression
test pins it.

**Status.** Holds; reproduced in the 2026-10-07 audit. Since #498, a recursive alias, a document
that expands past `YamlLoader.MAX_EXPANDED_NODES` through aliases, and a repeated key all fail
as located `ConfigValidationException`s; `YamlAliasBoundsTest` covers them.

**Violation looks like.** `compose` swapped for `load` / `loadAs` together with a non-safe
constructor; `new Yaml()` without a constructor argument; a `Constructor` / custom
`BaseConstructor`; or `LoaderOptions.setTagInspector(...)` allowing global tags.

---

### SEC-2 — configuration values stay out of framework logs

**Surface.** `tiko-config` coercion and validation messages, logged by the default error
handler (`DefaultErrorHandler`, `ConfigurationFailure` case, one WARNING per issue).

**Threat.** Secrets placed in configuration (passwords, tokens) appearing in logs.

**Rule.** Framework log lines and validation messages describe *where* and *what kind* of
value is wrong, never the value itself.

**Enforced by.** Code and test:
- Scalar coercers (`Coercers`) say what kind of value was expected and never quote the
  rejected input or out-of-range number. `BindContext` prefixes the field path and source
  location.
- The `Set<X>` coercer (`CompositeCoercers`) logs a duplicate's list index, not the element.
- `ConfigValuesStayOutOfMessagesTest` checks, with a secret-shaped input, that each scalar
  rejection path, the anchored type-mismatch issue and the duplicate warning omit the value.

**Status.** Holds. Coercion, binding and the duplicate warning were fixed in #474, before
which the duplicate warning and type-mismatch messages quoted the value. #493 fixed two more
messages, both now pinned by tests:
- The malformed-YAML message passed an alias name through: an unquoted `password: *secret`
  was reported as `found undefined alias secret`. `YamlLoader` now reports only `found
  undefined alias` or `found duplicate anchor`; `ConfigValuesStayOutOfMessagesTest` covers it.
- `tiko.shutdownTimeout` validation quoted the rejected duration;
  `TikoResolveShutdownTimeoutTest` covers it.

**Violation looks like.** A log call or exception message that concatenates a resolved
configuration value.

---

### SEC-3 — Kafka payloads deserialize into the declared type only

**Surface.** `tiko-kafka` ingest: record bytes → `KafkaSerializer.deserialize(bytes, type)`.

**Threat.** Polymorphic deserialization gadgets — a payload choosing which class to
instantiate.

**Rule.** Untrusted bytes are read into the bridge method's declared payload type. No Jackson
default typing, no type information taken from the payload, no Java object serialization.

**Enforced by.** Code: `JsonKafkaSerializer.deserialize` → `MAPPER.readValue(bytes, type)`;
`ThreadPerTopicRunner` passes `source.payloadType()`. `MAPPER` is a plain `ObjectMapper`
(no `activateDefaultTyping`). No `ObjectInputStream` in `tiko-*/src/main`. Users may plug in
their own `KafkaSerializer`; its behaviour is theirs.

**Status.** Holds.

**Violation looks like.** `activateDefaultTyping` / `enableDefaultTyping`, `@JsonTypeInfo`
with `Id.CLASS` on a framework type, `ObjectInputStream`, or a deserialize call whose target
type comes from the record (header, field) instead of the bridge.

---

### SEC-4 — failed records are reported without their payload

**Surface.** `tiko-kafka` ingest failures → `KafkaIngestError` → `ErrorHandler`.

**Threat.** Raw (possibly sensitive) record contents written to logs.

**Rule.** Error reports carry topic, partition, offset, headers and the cause — never the
record value.

**Enforced by.** Type: `KafkaIngestError(String topic, int partition, long offset, Headers
headers, Throwable cause)` and `KafkaRecordDeadLettered` have no payload component.
`DefaultErrorHandler` logs a `TransportError` as `Transport {0} error at {1}: {2}` (transport
name, `location()`, cause), or without `at {1}` when the location is empty. Kafka's
`location()` is `topic-partition@offset` (the topic alone for a poll failure or egress), built
from coordinates only (`KafkaErrorLocationTest`). Egress is different: `KafkaEgressError(topic, Object event, cause)` carries the
outbound event. `DefaultErrorHandler` doesn't render it, but the record's `toString()`
includes it.

**Status.** Holds. Fixed in #494: the `cause` in that WARNING used to be Jackson's exception,
which quoted the offending field value in full (`from String "SSN-123-45-6789"`) on every
`SEEK` redelivery. `JsonKafkaSerializer` now throws a message naming only the failure kind,
field path and position, and doesn't chain Jackson's exception; `JsonKafkaSerializerPayloadLeakTest`
checks the whole printed stack trace. A user-supplied `KafkaSerializer` controls its own
messages. Since #516 the log line names the record (`orders-3@1042`) so an operator can find it.

**Violation looks like.** A payload/`byte[]` component added to `KafkaIngestError`, a log
call that renders `record.value()` or a `KafkaEgressError` (its `toString()`), a `location()`
built from a header or event value, or a
deserializer exception message passed to a log line unfiltered.

---

### SEC-5 — annotation values enter generated code as escaped literals

**Surface.** `tiko-processor` and `tiko-kafka-processor` code generation: Java sources, and
the resource files written next to them (`META-INF/tiko/configs.txt`, `components.txt`,
`test-shadows.properties`, `topology.json`, `topology-kafka.json`, `config-schema.json`,
`META-INF/services/*`).

**Threat.** Code injection into a user's build through annotation string values (topic names,
event names, qualifiers, configuration keys), or annotation text that rewrites a generated
resource the runtime reads back.

**Rule.** String values reach generated code as escaped Java literals — through JavaPoet `$S`,
or, where a literal is assembled by hand, through `CodeLiterals.javaString` (the #333 fix).
`$L` carries only identifiers, numbers and code assembled from escaped pieces.

**Enforced by.** Code, per call site (check every generator in both processors, not one):
- `KafkaTransportBootstrapGenerator` emits topics and names via `$S` (descriptor
  `list.add(new $T($S, $S, …))` statements). `$L` carries method names, plus `partitionKey`,
  an annotation string that is safe only because `PartitionKeyValidator`
  (`KafkaAnnotationProcessor`) rejects anything but an accessor chain before generation.
- `ContainerGenerator` (qualifier lookups) and `ComponentFactoryGenerator` (qualifier
  arguments) build quoted strings by hand but escape them with `CodeLiterals.javaString`.
- `ConfigBinderGenerator.quotedJoin` joins the `@Key` values as `$S` code blocks; the joined
  block goes through `$L` (`checkUnknownKeys(node, $S, $T.of($L))`, top-level and nested
  records). `KeyLiteralEscapingTest` pins quote, backslash, line-break, unicode-escape and
  code-injection keys at both call sites.

- Resource files:
  - `topology.json` / `topology-kafka.json` escape names.
  - `ConfigurationValidator` rejects, with a compile error, a `@Configuration` prefix holding a
    control character or `=`, so it can't add or alter a `configs.txt` entry. Pinned by
    `ConfigPrefixLineSafetyTest`.
  - `ConfigSchemaWriter` escapes `@Default` strings with `JsonWriter.escape`, and writes numeric
    defaults from the parsed value, or as a string when not finite. Pinned by
    `ConfigSchemaDefaultsJsonTest`, which parses the schema as JSON.

**Status.** Holds. Generated Java was fixed in #479; before it, a crafted `@Key` compiled into
extra statements in the generated binder. Resources were fixed in #496. Before it, a `prefix`
containing a line break added lines to `configs.txt`, which `AggregatingContainer` reads back
with `Class.forName`. `@Default` control characters, and `NaN`, `Infinity`, `+1`, `1d` or hex
`double` defaults, produced invalid `config-schema.json`.

**Violation looks like.** `$L` with an annotation string value, or hand-built quoting
(`"\"" + value + "\""`) without `CodeLiterals.javaString`, in an `addStatement` / `addCode`
argument; or annotation text written to a generated resource without escaping it for that
format (line-oriented files: line breaks and separators; JSON: control characters, non-finite
numbers).

---

### SEC-6 — external text is never a log format pattern

**Surface.** Framework logging (`System.Logger`, `TikoLog`).

**Threat.** Format-string confusion or log forging when external text is treated as a
pattern.

**Rule.** `TikoLog.log` patterns are constants; external values go only into arguments.

**Enforced by.** Code: `TikoLog.log(logger, level, pattern, args…)` formats with
`MessageFormat` only when arguments exist; every call site passes a literal pattern.
Messages built by concatenation (e.g. `ThreadPerTopicRunner.seekSafely`) use framework
values (topic-partition, offset) and the non-formatting `System.Logger.log(Level, String,
Throwable)`.

**Status.** Holds.

**Violation looks like.** `TikoLog.log(…, someVariable, …)` as the pattern argument.

---

### SEC-7 — the MCP server is local and confined to the project

**Surface.** `tiko-mcp`.

**Threat.** Network exposure of build metadata, or reads outside the project.

**Rule.** stdio transport only, no network listener; file reads stay under the project root
given as `args[0]`.

**Enforced by.** Code: `TikoMcpServer.main` (`Paths.get(args[0])`), `McpStdioBridge`. No
`ServerSocket` / `HttpServer` in `tiko-mcp/src/main`. Confinement: `ProjectFiles.isInside`
(real path, links resolved, must be under the root's real path) gates every file
`TopologyStore.findFiles` loads and every match in `GetGeneratedArtifactTool`, whose walks no
longer pass `FOLLOW_LINKS`. Pinned by `TopologyStoreSymlinkTest` (all four files) and
`GetGeneratedArtifactToolSymlinkTest`; both need symlink support and skip where the OS refuses
one (Windows without Developer Mode), so CI on Linux is where they run.

**Status.** Holds (fixed in #475; symlinks that stay inside the project still work).
Re-checked 2026-10-07 with directory junctions and traversal arguments. Both directory walks go
through `ProjectFiles.find`, which enters each directory once by its real path, so a link cycle
under the project root no longer stops the server from starting (#499; `LinkCycleTest`).

**Violation looks like.** A socket/HTTP transport, `FileVisitOption.FOLLOW_LINKS`, or a tool
argument resolved into a path outside the root.

---

### SEC-8 — no class loading driven by external input

**Surface.** `tiko-runtime` container bootstrap and aggregation; `tiko-kafka` serializer
selection and client configuration.

**Threat.** Attacker-chosen classes loaded or instantiated at runtime.

**Rule.** Reflection and `Class.forName` take names only from the application's own
classpath (processor-generated resources, `ServiceLoader`), never from configuration values,
messages or network input.

**Enforced by.** Code: `AggregatingContainer` and `Tiko` load generated container, registry
and index classes named by build-generated resources; transports are discovered through
`ServiceLoader<TransportBootstrap>`. `KafkaBootstrapSupport` instantiates the serializer class
named by the `@KafkaSource` / `@KafkaSink` annotation, and `tiko.kafka.serializer` only picks
among the serializers `ServiceLoader<NamedKafkaSerializer>` found.

**Status.** Holds. Two inputs are trusted by design rather than enforced:
- Generated resources: `configs.txt` lines come from annotation text (SEC-5, #496).
- `tiko.kafka.consumer-properties` / `producer-properties` go to the Kafka clients unchanged,
  and the clients instantiate classes named there (`interceptor.classes`, `config.providers`,
  …). Configuration is operator-controlled, so this is the operator's surface, not an
  attacker's.

**Violation looks like.** `Class.forName` / `getDeclaredConstructor().newInstance()` on a
string from config, a record, or an HTTP request.

---

### SEC-9 — dependencies are managed and their advisories visible

**Surface.** Maven dependencies, transitive ones included; Jackson shaded into `tiko-kafka`;
the `tiko-mcp` fat jar.

**Threat.** Shipping a dependency with a known vulnerability.

**Rule.** Versions are managed in `tiko-bom` and the root `dependencyManagement`; every
shipped dependency, shaded ones included, is covered by an advisory check before release.

**Enforced by.** Dependabot alerts and Dependency Review over GitHub's dependency graph.
`.github/workflows/dependency-submission.yml` submits every module's fully resolved Maven tree
on each push to `main` (and on pull requests from this repository), so transitive and bundled
artifacts appear at the version that ships. `DependencySubmissionWorkflowTest` fails the build
if that workflow disappears, loses its `main` trigger or excludes a module. On top of that,
`tiko-kafka/pom.xml` declares every Jackson artifact it shades, at the version the root
`dependencyManagement` pins (`ShadedDependenciesDeclaredTest`). The `tiko-security` release
gate's global-advisory query cross-checks the bundled coordinates of both modules.

**Status.** Holds. Fixed in #476 for `tiko-kafka`, whose shaded `jackson-core` /
`jackson-annotations` the graph didn't list. Fixed in #492 for `tiko-mcp`, whose bundled
runtime tree (MCP SDK, `reactor-core`, `json-schema-validator`, `jackson-dataformat-yaml`, …)
the graph didn't list.

**Violation looks like.** A new shaded or transitive dependency with no advisory coverage, or
the dependency-submission workflow disabled, failing on `main`, or excluding a module.

---

### SEC-10 — release credentials and workflows are contained

**Surface.** GitHub Actions workflows and release secrets.

**Threat.** Credential exposure or a compromised third-party action in CI.

**Rule.** Release secrets (`CENTRAL_*`, `GPG_*`, `RELEASE_PUSH_TOKEN`) are referenced only
in `release.yml`; artifacts are GPG-signed; workflows run with least-privilege
`permissions`.

**Enforced by.** `.github/workflows/release.yml` (sole user of those secrets);
`maven-gpg-plugin` in the root `pom.xml` `release` profile. Every third-party action is pinned
to a full commit SHA with its version in a trailing `# vX.Y.Z` comment;
`WorkflowActionPinningTest` (in `tiko-archetype`) fails the build on a tag reference or a
missing version comment, and `.github/dependabot.yml` proposes the updates.

**Status.** Holds (fixed in #477; before it, actions were referenced by movable tags such as
`@v4`).

**Violation looks like.** A release secret in another workflow, `permissions: write-all`,
or an unpinned/new third-party action.

---

### SEC-11 — failures can't run away

**Surface.** `tiko-runtime` async dispatch; `tiko-kafka` ingest loop.

**Threat.** Resource exhaustion — unbounded queues, or a tight retry loop flooding logs and
CPU.

**Rule.** The async queue is bounded with an overflow policy; ingest failures don't produce
an unbounded tight loop.

**Enforced by.** `TikoOptions` (`queueCapacity` default 1024, `OverflowPolicy`).
`ThreadPerTopicRunner.seekWithBackoff` pauses only the failing partition after a `SEEK` for
`tiko.kafka.seek-backoff` (default `PT0.5S`), doubling per consecutive failure up to
`seek-backoff-max` (default `PT30S`); pinned by `KafkaSeekBackoffTest` and
`SeekBackoffDelayTest`.

**Status.** Holds. #478 fixed the defaults: a poison record that redelivered ~37,000 times per
second now redelivers at the backoff pace, and `seek-backoff: PT0S` still opts back into
immediate redelivery. #495 closed the misconfiguration gap: `ThreadPerTopicRunner` rejects a
non-positive `poll-timeout` (also the pause after a failed `poll()`), a negative
`seek-backoff`, and a `seek-backoff-max` below `seek-backoff` when it is constructed at
startup. `KafkaTimingValidationTest` pins each case.

---

### SEC-12 — test wiring never switches on in production by itself

**Surface.** `tiko-runtime` test mode (`Tiko.create` detecting
`META-INF/tiko/test-container.properties`; `AggregatingContainer` shadow overrides from
`test-shadows.properties`) and the `tiko-processor` output that writes those files.

**Threat.** A container in production silently resolving test doubles (fakes that skip
checks, in-memory stores) because a test-component jar landed on the classpath.

**Rule.** Test wiring only activates when the application opted into it explicitly, and a
`@TestComponent` outside a test context is reported, not applied silently.

**Enforced by.** Not yet enforced.

**Status.** Gap: any `test-container.properties` on the classpath switches the container to
test mode, and the processor writes these files for main sources too. Reproduced: a fixtures
jar with a `@TestComponent` replaced a real component in a production container, with nothing
logged. Tracked in #497. `replaceTransport` / `FakeKafkaTransport` are explicit code-level
opt-ins and are not part of this gap.

**Violation looks like.** Test-only behaviour selected by classpath presence alone, or a test
seam reachable through configuration.

---

### SEC-13 — generated projects start safe

**Surface.** `tiko-archetype` output: the project template, the `mcp.json` that
`archetype-post-generate.groovy` turns into `.mcp.json` (which runs
`jbang run io.github.tomas-samek:tiko-mcp:<version>` when an agent opens the project), and the
agent-instruction files it copies (`.ai-skills`, `CLAUDE.md`, `AGENTS.md`, `.cursor`,
`.junie`, `.github`).

**Threat.** Every project created from the archetype inheriting an unpinned or unexpected
executable, a secret, a permissive default, or agent instructions that don't come from the
canonical repository sources.

**Rule.** The template references only exact released coordinates from Maven Central, and
contains no secrets or permissive defaults. The agent-instruction files are copies of the
repository's canonical sources.

**Enforced by.** `release.yml` bumps the `tiko-mcp` coordinate in `mcp.json` to the released
version. `ArchetypeBundledSkillsInSyncTest` keeps the bundled skills identical to the
repository's. The template declares no repositories besides Central.

**Status.** Holds (2026-10-07 audit).

**Violation looks like.** A `LATEST`/range/SNAPSHOT coordinate or a non-Central repository in
the template, a credential or token in any template file, or an agent-instruction file edited
in the archetype instead of at its canonical source.
