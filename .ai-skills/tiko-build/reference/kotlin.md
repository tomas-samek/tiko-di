# tiko-build reference — Kotlin (Gradle + KSP)

> Read this when: the service is written in Kotlin, or a Gradle build is involved.

Tiko's annotation processors run on Kotlin through KSP2, from the same jars
Java uses (`tiko-processor`, and `tiko-kafka-processor` for Kafka). The generated
container and factories stay Java; the Kotlin Gradle toolchain compiles them. There is
no runtime reflection on this path either.

## Gradle setup

Reference build: [`tiko-examples-gradle/kotlin-quickstart`](../../../tiko-examples-gradle/kotlin-quickstart)
(Gradle 9.8.2, Kotlin 2.4.21, JVM 21).

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.13"   // KSP2 — this is the floor
}

dependencies {
    implementation("io.github.tomas-samek:tiko-api:<version>")
    implementation("io.github.tomas-samek:tiko-runtime:<version>")
    ksp("io.github.tomas-samek:tiko-processor:<version>")
    // Kafka bridges: also ksp("io.github.tomas-samek:tiko-kafka-processor:<version>")
    //                and implementation("io.github.tomas-samek:tiko-kafka:<version>")
}

kotlin { jvmToolchain(21) }
```

- **KSP2 only.** `com.google.devtools.ksp` 2.3.13 or newer. KSP1 is not supported.
- The processors go on the `ksp(...)` configuration. There are no separate `*-ksp` artifacts.
- No Tiko Gradle plugin exists; use the standard KSP plugin.
- Gradle is the supported build for Kotlin.
- **Maven + Kotlin:** may work with any Maven plugin that runs a KSP `SymbolProcessorProvider`;
  untested.

## What does not work on KSP yet

- `@TikoTest` and `@TestComponent` (the `tiko-test` module) don't work on KSP yet. Planned.
- `suspend` event handlers and `suspend` `@KafkaSource` bridges: rejected until Tiko's
  coroutine support.

## Where KSP differs from javac

- **Sources another processor generates in a later round.** On javac, Tiko generates the
  container in the first round that has components, so a Tiko-annotated source another
  processor writes in a later round is an error ("appeared in a later annotation-processing
  round", #334). On KSP, Tiko waits for the first round without new Tiko sources, so such
  components are wired into the container.

## Construct rules

A Kotlin construct that maps 1:1 onto a Java construct Tiko already supports is supported.
One that would add new DI semantics is rejected at compile time with error kind
`KOTLIN_UNSUPPORTED`. Anything Tiko rejects in Java it rejects in Kotlin.

| Construct | Result | Fix when rejected |
|---|---|---|
| `final` (default) class as a component | Supported. Proxies are interface-based; nothing is subclassed. | — |
| `internal` class, constructor or member | Supported. Generated Java calls `internal` members by their JVM (mangled) names. | — |
| Use-site target `@param:` on constructor properties | Supported. `@Named` and `@Pick` target parameters only, so write `@param:Named("x")`; `@field:Named` does not compile. | — |
| Default parameter values | Supported, but never optional: Tiko injects the primary constructor with **all** parameters; defaults are not used. | — |
| Primary + secondary constructors, no `@Inject` | The primary constructor is injected. (In Java, two public constructors without `@Inject` are an error.) | — |
| `@Inject` on a secondary constructor | That constructor is injected instead of the primary one. | — |
| `@JvmOverloads` constructor | The primary constructor is injected with all its parameters; the extra JVM overloads are ignored. | — |
| Interface with properties (`val id: String`, `var name: String`) behind an `EVENT` proxy | Supported. The proxy overrides the JVM accessors (`getId()`, `getName()` / `setName(...)`). | — |
| `@Configuration` on a `@JvmRecord data class` | Supported: bound like a Java record. Components are the primary constructor's parameters, in order; `@Key` / `@Default` go on those parameters. A Kotlin default value (`val port: Int = 8080`) or a nullable type (`String?`) does not make a key optional: the key stays required, and boot fails without it. | For a key that may be absent, use `@Default("8080")` (a default value) or `Optional<T>` (no value). |
| `@Configuration` on a plain `data class` or `class` | Rejected: `@Configuration` needs a record. | Annotate it `@JvmRecord` (a `data class` with `val` parameters). |
| `@Configuration` on a Java record (also one reached through a component) that declares its canonical (or compact) constructor next to another constructor whose parameters all match its accessors | Rejected on KSP: KSP doesn't expose a Java record's header, so either constructor could be the canonical one. javac builds read the header and are unaffected. A record compiled into a library always counts as declaring its canonical constructor, so one with any extra such constructor (a no-arg convenience constructor, say) is rejected too. | Drop the declared canonical constructor, or give the other constructor a parameter no accessor matches; for a library record, bind a record of your own with the same components. |
| `vararg` constructor parameter | An array dependency, as on Java (`B[]`); normally a missing dependency. | Declare a plain `B` parameter, or a `Picker<B>`. |
| Java / platform types | Count as non-null. | — |
| `@Produces` / `@EventHandler` on a top-level function | Rejected (`KOTLIN_UNSUPPORTED`): no component instance to call it on. | Move it into a `@Component` class. |
| `object` as a component | Rejected: Tiko creates and scopes every instance. | Use a `class` with `scope = SINGLETON`. |
| `companion object` as a component | Rejected. | Use a `class` with `scope = SINGLETON`. |
| `@Produces` / `@EventHandler` inside an `object` | Rejected: objects can't be components. | Move it into a `@Component` class. |
| `@Produces` / `@EventHandler` inside a `companion object` | Rejected. | Move it to the enclosing `@Component` class. |
| `@Produces` (or other DI function annotation) on an extension function | Rejected: Tiko injects parameters only, not a receiver. | Declare a regular `fun` taking the receiver as a parameter. |
| `@get:` / `@set:` DI annotation on a property (`@get:Produces`, `@set:PostConstruct`, ...) | Rejected: Tiko reads DI annotations on functions. | Declare a `fun` instead. |
| `suspend` `@EventHandler` | Rejected until coroutine support. | Make it a regular `fun`. |
| `suspend` `@Produces` / `@PostConstruct` / `@PreDestroy` | Rejected: the container calls them outside a coroutine. | Drop `suspend`. |
| Value class (`@JvmInline value class`) as a component, injected type, `@Produces` return or parameter, or handler event | Rejected: on the JVM it is passed as its underlying value under a mangled function name, so generated Java can't use it. | Use a regular class (a `data class` keeps value semantics). |
| DI function whose JVM name Java can't call (e.g. `fun new()`) | Rejected. | Rename it, or set a callable name with `@JvmName`. |
| `@KafkaSource` / `@KafkaSink` on a top-level function, or inside an `object` / `companion object` | Rejected: no component instance to call it on. | Move it into (or, from a companion, to the enclosing) `@Component` class. |
| `@KafkaSource` / `@KafkaSink` on an extension function | Rejected: the bootstrap passes only the payload (and `KafkaContext`). | Declare a regular `fun` taking the receiver as a parameter. |
| `suspend` `@KafkaSource` | Rejected until coroutine support. | Make it a regular `fun`. |
| `suspend` `@KafkaSink`, or a value class in a bridge's signature | Rejected. | Drop `suspend`; use a regular class. |

## Nullability

`T?` never means "optional dependency". Null is legitimate only when the **provider**
may return it: a `@Produces` function whose return type is `T?`.

Nullability must match **exactly** between provider and injection point. A mismatch is
error kind `NULLABILITY_MISMATCH`; the message names the producer and the fix.

| Case | Result |
|---|---|
| `@Produces fun foo(): Foo?` injected as `Foo?` (or `Provider<Foo?>`) | OK |
| `@Produces fun foo(): Foo?` injected as `Foo` | Rejected. Declare the parameter `Foo?`, or make the producer return `Foo`. |
| Non-null provider (component, `@Produces` returning `Foo`) injected as `Foo?` | Rejected. Drop the `?`. |
| `Foo?` with no provider of `Foo` | The usual missing-dependency error. |
| Kotlin nullable producer injected into a Java parameter | Rejected: Java counts as non-null. Make the producer non-null. |
| `Provider<Foo>?` or `Picker<Foo>?` | Rejected: the container always passes the wrapper. Drop the `?`. |
| `Picker<Foo?>` | Rejected: a `Picker` can't hand out null. |
| `Picker<Foo>` where any reachable producer returns `Foo?` | Rejected. Make that producer non-null, or inject `Foo?` from it directly. |
| Nullable `EVENT` producer injected into a longer-lived component | Rejected: that component gets a proxy, which is never null. Make the consumer `EVENT`-scoped, or the producer non-null. |

A nullable `SINGLETON` producer that returns null is cached as null (invoked once); a
nullable `EVENT` producer is invoked once per unit.

## Example

```kotlin
@Component(scope = Scope.SINGLETON)
internal class Auditor @Inject constructor(
    private val log: EventLog,
    @param:Named("greeting") private val greeting: String,
    @param:Named("nickname") private val nickname: String?,   // provider returns String?
    private val label: Label = Label(),                         // still injected, not optional
)
```

Errors are reported against the `.kt` source line, and also written to
`META-INF/tiko/wiring-errors.json`.
