# Security Model

The registry `tiko-security` checks pull requests, release deltas and audits against. Each
entry names an attack surface in Tiko's own code or supply chain, the threat, the rule that
guards it, where the rule is enforced, and its current status. It covers Tiko itself — not
the security of applications built on it (authentication, authorization and the like are
plug-ins). Disclosure policy lives in [`SECURITY.md`](../SECURITY.md).

Facts verified against the code on 2026-10-05. Each anchor names a file and a symbol; line
numbers are a convenience and drift.

---

### SEC-1 — YAML configuration loads data only

**Surface.** `tiko-config` reading `application.yaml` and profile overlays.

**Threat.** A YAML document with global tags (`!!java.…`, `!!javax.…`) instantiating
arbitrary classes during load.

**Rule.** YAML is parsed with SnakeYAML's `SafeConstructor` only — maps, lists and scalars.

**Enforced by.** Code: `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java`,
`new Yaml(new SafeConstructor(opts))` (line 46). No regression test pins it.

**Status.** Holds.

**Violation looks like.** `new Yaml()` without a constructor argument, a `Constructor` /
custom `BaseConstructor`, or `LoaderOptions` allowing global tags.

---

### SEC-2 — configuration values stay out of framework logs

**Surface.** `tiko-config` coercion and validation messages, logged by the default error
handler (`DefaultErrorHandler`, `ConfigurationFailure` case, one WARNING per issue).

**Threat.** Secrets placed in configuration (passwords, tokens) appearing in logs.

**Rule.** Framework log lines and validation messages describe *where* and *what kind* of
value is wrong, never the value itself.

**Enforced by.** Convention (not enforced).

**Status.** Gap:
- `CompositeCoercers` (`Set<X>` coercer) logs `duplicate value '<value>' deduped` at WARNING.
- Scalar coercers include the rejected string in `CoercionException` messages
  (`expected integer, got string "<value>"`), which reach the WARNING log through
  `ConfigurationFailure`. Tracked in #474.

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
headers, Throwable cause)` has no payload component. `DefaultErrorHandler` logs a
`TransportError` as `Transport {0} error: {1}` (transport name + cause).

**Status.** Holds. Note: a parser's exception message (the `cause`) can quote a token from
the rejected input.

**Violation looks like.** A payload/`byte[]` component added to `KafkaIngestError`, or a log
call that renders `record.value()`.

---

### SEC-5 — annotation values enter generated code as escaped literals

**Surface.** `tiko-processor` and `tiko-kafka-processor` code generation.

**Threat.** Code injection into a user's build through annotation string values (topic names,
event names, qualifiers, configuration keys).

**Rule.** String values reach generated code as escaped Java literals — through JavaPoet `$S`,
or, where a literal is assembled by hand, through `CodeLiterals.javaString` (the #333 fix).
`$L` carries only identifiers, numbers and code assembled from escaped pieces.

**Enforced by.** Code, per call site (check every generator in both processors, not one):
- `KafkaTransportBootstrapGenerator` emits topics and names via `$S` (descriptor
  `list.add(new $T($S, $S, …))` statements); `$L` carries method names.
- `ContainerGenerator` (qualifier lookups) and `ComponentFactoryGenerator` (qualifier
  arguments) build quoted strings by hand but escape them with `CodeLiterals.javaString`.
- `ConfigBinderGenerator.quotedJoin` joins the `@Key` values as `$S` code blocks; the joined
  block goes through `$L` (`checkUnknownKeys(node, $S, $T.of($L))`, top-level and nested
  records). `KeyLiteralEscapingTest` pins quote, backslash, line-break, unicode-escape and
  code-injection keys at both call sites.

**Status.** Holds (fixed in #479; before it, a crafted `@Key` compiled into extra statements in
the generated binder).

**Violation looks like.** `$L` with an annotation string value, or hand-built quoting
(`"\"" + value + "\""`) without `CodeLiterals.javaString`, in an `addStatement` / `addCode`
argument.

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

**Violation looks like.** A socket/HTTP transport, `FileVisitOption.FOLLOW_LINKS`, or a tool
argument resolved into a path outside the root.

---

### SEC-8 — no class loading driven by external input

**Surface.** `tiko-runtime` container bootstrap and aggregation.

**Threat.** Attacker-chosen classes loaded or instantiated at runtime.

**Rule.** Reflection and `Class.forName` take names only from the application's own
classpath (processor-generated resources, `ServiceLoader`), never from configuration values,
messages or network input.

**Enforced by.** Code: `AggregatingContainer` and `Tiko` load generated container, registry
and index classes named by build-generated resources; transports are discovered through
`ServiceLoader<TransportBootstrap>`.

**Status.** Holds.

**Violation looks like.** `Class.forName` / `getDeclaredConstructor().newInstance()` on a
string from config, a record, or an HTTP request.

---

### SEC-9 — dependencies are managed and their advisories visible

**Surface.** Maven dependencies; Jackson shaded into `tiko-kafka`.

**Threat.** Shipping a dependency with a known vulnerability.

**Rule.** Versions are managed in `tiko-bom` and the root `dependencyManagement`; every
shipped dependency, shaded ones included, is covered by an advisory check before release.

**Enforced by.** Dependabot alerts and Dependency Review (declared dependencies); the
`tiko-security` release gate's global-advisory query (shaded coordinates).

**Status.** Gap: GitHub's dependency graph lists `jackson-databind` and
`jackson-datatype-jsr310` but not `jackson-core` / `jackson-annotations`, which
`tiko-kafka` shades (`<include>com.fasterxml.jackson.core:*</include>`). Automated alerts
don't cover them. Tracked in #476.

**Violation looks like.** A new shaded or transitive dependency with no advisory coverage.

---

### SEC-10 — release credentials and workflows are contained

**Surface.** GitHub Actions workflows and release secrets.

**Threat.** Credential exposure or a compromised third-party action in CI.

**Rule.** Release secrets (`CENTRAL_*`, `GPG_*`, `RELEASE_PUSH_TOKEN`) are referenced only
in `release.yml`; artifacts are GPG-signed; workflows run with least-privilege
`permissions`.

**Enforced by.** `.github/workflows/release.yml` (sole user of those secrets);
`maven-gpg-plugin` in the root `pom.xml` `release` profile.

**Status.** Gap: third-party actions are pinned by version tag (`@v4`), not by commit SHA. Tracked in #477.

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

**Status.** Holds (fixed in #478: a poison record that redelivered ~37,000 times per second
now redelivers at the backoff pace). Setting `seek-backoff: PT0S` opts back into immediate
redelivery.
