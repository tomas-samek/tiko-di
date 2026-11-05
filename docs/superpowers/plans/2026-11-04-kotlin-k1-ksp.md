# Kotlin K1 (KSP processing) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `tiko-processor` and `tiko-kafka-processor` run on both javac and KSP2, from the
same jars, through a new shared `tiko-processor-core` layer. Kotlin constructs are then
supported or rejected at compile time, and a Gradle + KSP Kotlin example proves it.

**Architecture:**
- `tiko-processor-core` defines a frontend-neutral view of declarations and types
  (`ClassDecl`, `MethodDecl`, `TypeRef`, ...) plus a `ProcessingEnv` covering diagnostics,
  output, a type oracle and classpath probing.
- Two adapters implement it: `core.javac` over `javax.lang.model`, and `core.ksp` over the KSP2
  API.
- All DI and Kafka logic (collection, validation, generation) is rewritten against core only.
  Each processor jar registers a javac `Processor` and a KSP `SymbolProcessorProvider`.
  Generated output stays Java (JavaPoet).

**Tech Stack:** Java 21, Maven, JavaPoet (existing), Google compile-testing (existing), KSP2
`symbol-processing-api` (provided), `dev.zacsweers.kctfork:core` + `:ksp` (test), Gradle +
`com.google.devtools.ksp` (example build only).

**Spec:** `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md` (section
"Milestone K1"). Executors read both.

## Global Constraints

- Java users see **no behaviour or generated-code change**. Every existing compile-testing
  suite in `tiko-processor` (93 test files) and `tiko-kafka-processor` (9) passes after each
  task. The only allowed test edits are mechanical import or package moves.
- After Task 7, no `javax.lang.model` / `javax.annotation.processing` import exists outside
  `io.tiko.processor.core.javac` and the two javac entry classes. The KSP API is imported only
  in `io.tiko.processor.core.ksp` and the two KSP entry classes.
- Validation and generation logic exists **once**. Frontend adapters only translate; they
  never decide.
- KSP2 only. The floor is the KSP2 release paired with the current Kotlin 2.x when Task 8
  starts. Look up the latest stable versions on Maven Central at that point (CLAUDE.md
  "Adding Dependencies") and pin them in `tiko-bom/pom.xml` and the root
  `<dependencyManagement>`. `symbol-processing-api` and `kotlin-stdlib` are `provided` in core.
- Generated output stays Java. Annotations stay `SOURCE`. No runtime reflection.
- The Kotlin construct rule from the spec: 1:1 Java mappings are supported, new DI semantics
  are rejected with a Tiko-format error (location, what's wrong, at least one suggested fix).
- Nullability must match **exactly** between provider and injection point. Java declarations
  (platform types) count as non-null. `T?` never means "optional dependency".
- `tiko-test` on KSP is **not** part of K1 (it's K1.5). On KSP, classpath probing returns
  "nothing found", and the docs say `@TikoTest` / `@TestComponent` don't work on KSP yet.
- House rules (CLAUDE.md): `var` for locals, `System.Logger` only, `@Generated` through
  `GeneratorAnnotations.generatedBy`, JUnit 5 + AssertJ, camelCase test names, no
  `Thread.sleep`, Spotless (`mvn -pl '!tiko-bom' spotless:apply`). Commits are single-line
  `type(scope): subject`. One PR per task, on its own branch off `main`.

## Review Focus

1. **A KSP round where another processor generates a `@Component` later.** The container must
   include it, as javac does today when generating at `processingOver`. Pinned by
   `KspRoundingTest` in Task 8.
2. **`internal` Kotlin members called from generated Java.** Generated code must use the JVM
   (mangled) name, or it compiles on KSP and then fails in javac. Pinned by
   `KotlinInternalMemberTest` in Task 9.
3. **A nullable `@Produces` SINGLETON that returns null.** The generated container must cache
   that null and return it, not re-invoke the producer. Java output must not change. Pinned by
   `NullableProducerCachingTest` and `JavaOutputUnchangedByNullabilityTest` in Task 9.
4. **Errors pointing at the right Kotlin source line.** Users read KSP diagnostics against
   `.kt` files. Pinned by `KspDiagnosticLocationTest` in Task 8.
5. **A Maven consumer with only `tiko-processor` in `annotationProcessorPaths`.** Core must
   arrive transitively. Pinned by running `tiko-examples` and an archetype-generated project in
   Task 1.

---

### Task 1: `tiko-processor-core` module with the shared helpers

Creates the module and moves the helpers both processors need today. No neutral model yet.

**Files:**
- Create: `tiko-processor-core/pom.xml`. Parent `tiko-parent`; depends on `javapoet`; packaging
  `jar`.
- Move: `tiko-processor/.../util/GeneratorAnnotations.java` → `tiko-processor-core/src/main/java/io/tiko/processor/core/GeneratorAnnotations.java`
- Move: `tiko-processor/.../util/CodeLiterals.java` → `.../core/CodeLiterals.java`
- Move: `tiko-processor/.../topology/JsonWriter.java` → `.../core/JsonWriter.java`
- Create: `.../core/StackTraces.java`. Moves `TikoAnnotationProcessor.formatStackTrace`
  (`TikoAnnotationProcessor.java:1364`) here as `public static String format(Throwable)`.
- Modify: `pom.xml` (add `<module>tiko-processor-core</module>` before `tiko-processor`, plus
  the `dependencyManagement` entry), `tiko-bom/pom.xml` (add an entry next to
  `tiko-processor`), `tiko-processor/pom.xml` and `tiko-kafka-processor/pom.xml` (depend on
  core, compile scope).
- Modify: `KafkaTransportBootstrapGenerator.java:57`. Replace the inline `@Generated` with
  `GeneratorAnnotations.generatedBy(KafkaTransportBootstrapGenerator.class)`.
- Modify: `KafkaTopologyWriter.java`. Use `JsonWriter` and drop the hand-rolled builder,
  keeping the output byte-identical.
- Modify: CLAUDE.md ("Logging in Framework Code" → `StackTraces.format`; "Generated Code
  Markings" → new package), `.ai-skills/tiko-release/SKILL.md:211` (artifact count 11 → 12,
  add `tiko-processor-core`), `docs/releasing.md` (same list).
- Test: `tiko-processor-core/src/test/java/io/tiko/processor/core/StackTracesTest.java`, and
  move `topology/JsonWriterTest.java` to core.

**Interfaces:**
- Produces: `io.tiko.processor.core.GeneratorAnnotations.generatedBy(Class<?>) : AnnotationSpec`,
  `io.tiko.processor.core.JsonWriter` (same public API as today),
  `io.tiko.processor.core.StackTraces.format(Throwable) : String`,
  `io.tiko.processor.core.CodeLiterals` (same API).

- [ ] **Step 1:** Write `StackTracesTest.formatsCauseChainOnIndentedLines`. Assert that a
  `RuntimeException("outer", new IllegalStateException("inner"))` formats to text containing
  `"\n  at "` and `"\nCaused by: java.lang.IllegalStateException: inner"`.
- [ ] **Step 2:** Run `mvn test -pl tiko-processor-core -am`. Expected: compile FAIL (the
  module or class doesn't exist).
- [ ] **Step 3:** Create the module and the moves above. Update imports in both processors.
- [ ] **Step 4:** Add `KafkaTopologyFragmentTest` coverage if it isn't already there. Then run
  `mvn -q install -DskipTests` and `mvn test -pl tiko-processor-core,tiko-processor,tiko-kafka-processor`.
  Expected: all green, and `KafkaTopologyFragmentTest` / `KafkaTransportBootstrapGeneratorTest`
  are unchanged.
- [ ] **Step 5 (Review Focus 5):** Run `mvn exec:java` in `tiko-examples/15_quickstart` and in
  `08_kafka_order_warehouse` (see memory: examples aren't run by CI). Generate a project from the
  archetype into `@TempDir`-style scratch and `mvn verify` it. Expected: exit code 0 everywhere,
  with core resolved transitively through `annotationProcessorPaths`. Write the commands to a
  log file and check `$?`; never pipe the exit code.
- [ ] **Step 6:** Commit `refactor(processor): extract tiko-processor-core with shared generator helpers`.

---

### Task 2: Neutral model API and the javac adapter

**Files (all under `tiko-processor-core/src/main/java/io/tiko/processor/core/`):**
- Create: `model/TypeRef.java`, `model/ClassDecl.java`, `model/MethodDecl.java`,
  `model/ParamDecl.java`, `model/FieldDecl.java`, `model/AnnotationRef.java`,
  `model/Location.java`, `model/Nullability.java`, `model/DeclKind.java`,
  `model/Visibility.java`, `model/SourceLanguage.java`
- Create: `ProcessingEnv.java`, `Diagnostics.java`, `CodeOutput.java`, `TypeOracle.java`,
  `ClasspathProbe.java`
- Create: `javac/JavacProcessingEnv.java`, `javac/JavacModel.java` (the `Element` /
  `TypeMirror` → model mapping), `javac/JavacTypeOracle.java`
- Test: `tiko-processor-core/src/test/java/io/tiko/processor/core/javac/JavacModelTest.java`,
  using compile-testing with a test-only processor that maps annotated elements and records
  the result.

**Interfaces (Produces). Later tasks use exactly these names:**

```java
enum Nullability { NON_NULL, NULLABLE }          // platform types map to NON_NULL
enum DeclKind { CLASS, INTERFACE, RECORD, ENUM, ANNOTATION, KOTLIN_OBJECT, KOTLIN_COMPANION }
enum Visibility { PUBLIC, PROTECTED, PACKAGE, PRIVATE, KOTLIN_INTERNAL }
enum SourceLanguage { JAVA, KOTLIN, CLASSPATH }

interface TypeRef {
    TypeName javaPoet();                 // what generators emit
    String erasedName();                 // FQN without type args; "int" for primitives
    List<TypeRef> typeArguments();
    Nullability nullability();
    boolean isPrimitive();
    Optional<ClassDecl> declaration();   // empty for primitives / type variables
}
interface ClassDecl {
    String qualifiedName(); ClassName className(); DeclKind kind(); Visibility visibility();
    boolean isAbstract(); boolean isFinal(); boolean isStatic(); SourceLanguage language();
    List<AnnotationRef> annotations(); Optional<AnnotationRef> annotation(String annotationFqn);
    List<MethodDecl> constructors(); Optional<MethodDecl> primaryConstructor(); // Java: empty
    List<MethodDecl> methods(); List<FieldDecl> fields();
    List<TypeRef> directSupertypes(); TypeRef asType(); Location location();
    Optional<ClassDecl> enclosingClass();
}
interface MethodDecl {
    String name(); String jvmName();     // jvmName differs for Kotlin internal / @JvmName
    boolean isConstructor(); boolean isStatic(); boolean isAbstract(); boolean isSuspend();
    boolean isTopLevel();                // Kotlin top-level function
    Visibility visibility(); TypeRef returnType(); List<ParamDecl> parameters();
    List<TypeRef> thrownTypes(); List<AnnotationRef> annotations();
    Optional<AnnotationRef> annotation(String annotationFqn);
    Optional<ClassDecl> owner(); Location location();
}
interface ParamDecl { String name(); TypeRef type(); boolean hasDefaultValue();
    List<AnnotationRef> annotations(); Optional<AnnotationRef> annotation(String fqn); Location location(); }
interface FieldDecl { String name(); TypeRef type(); List<AnnotationRef> annotations(); Location location(); }
interface AnnotationRef {                 // values include declared defaults
    String annotationName();
    Optional<Object> value(String member); // String, Boolean, Integer, Long, enum constant name as String,
                                           // TypeRef for Class values, List<Object> for arrays,
                                           // AnnotationRef for nested annotations
    String string(String member); boolean bool(String member); int integer(String member);
    List<TypeRef> types(String member); List<String> strings(String member); String enumName(String member);
    Location location();
}
interface Location {}                    // opaque; only Diagnostics consumes it

interface Diagnostics {
    void error(Location at, String message); void warning(Location at, String message);
    void note(Location at, String message); boolean hasErrors();
}
interface CodeOutput {
    void writeJava(JavaFile file, List<ClassDecl> originating, boolean aggregating) throws IOException;
    void writeResource(String path, String content, List<ClassDecl> originating) throws IOException;
}
interface TypeOracle {
    boolean isAssignable(TypeRef from, TypeRef to);   // generics-aware, as javac's Types.isAssignable
    boolean isSameType(TypeRef a, TypeRef b);
    TypeRef erasure(TypeRef t);
    Optional<ClassDecl> findClass(String qualifiedName);
}
interface ClasspathProbe { Optional<String> readResource(String path); } // KSP: always empty in K1
interface ProcessingEnv {
    Diagnostics diagnostics(); CodeOutput output(); TypeOracle types(); ClasspathProbe classpath();
    Map<String, String> options(); SourceVersionInfo sourceVersion();
    List<ClassDecl> classesAnnotatedWith(String annotationFqn);
    List<MethodDecl> methodsAnnotatedWith(String annotationFqn);   // includes Kotlin top-level
}
```

`SourceVersionInfo` is a record `(int release)`. Make it only as rich as
`SupportedSourceVersionTest` needs. `JavacProcessingEnv` is constructed as
`JavacProcessingEnv.of(ProcessingEnvironment, RoundEnvironment)` and is created anew each
round.

- [ ] **Step 1:** Write `JavacModelTest`, a parameterized test (`Arguments.of(name, source, assertion)`)
  with these rows:
  - `classAnnotationValuesIncludeDefaults`: `@Component` with no `scope` gives
    `enumName("scope") == "PROTOTYPE"`;
  - `classValueMapsToTypeRef`: `@Pick(Foo.class)` gives `types("value").get(0).erasedName() == "p.Foo"`;
  - `javaTypesAreNonNull`;
  - `primitiveErasedName` (`int`);
  - `genericTypeArguments` (`Provider<Foo>` gives one argument, `p.Foo`);
  - `jvmNameEqualsNameInJava`;
  - `javaHasNoPrimaryConstructor`;
  - `isAssignableHonoursGenerics` (`List<String>` is not assignable to `List<Object>`;
    `ArrayList<String>` is assignable to `List<String>`);
  - `diagnosticLandsOnElement` (an error at a method's `Location` reports that method's line
    through compile-testing `hadErrorContaining(...).inFile(...).onLine(n)`).
- [ ] **Step 2:** Run `mvn test -pl tiko-processor-core -Dtest=JavacModelTest`. Expected:
  compile FAIL.
- [ ] **Step 3:** Implement the interfaces and the javac adapter. `Location` wraps the
  `Element`; `TypeRef` wraps the `TypeMirror` and computes `javaPoet()` with
  `TypeName.get(mirror)`. `TypeOracle` delegates to `Types`. `ClasspathProbe` keeps today's
  `Filer.getResource(CLASS_PATH, ...)` behaviour, including returning empty on
  `IOException` / `FilerException`, exactly as `TikoAnnotationProcessor.java:458` and `:1352`
  handle it.
- [ ] **Step 4:** Run the test. Expected: PASS.
- [ ] **Step 5:** Commit `feat(processor-core): add neutral declaration model and javac adapter`.

---

### Task 3: Port `tiko-kafka-processor` to core (the pilot)

**Files:**
- Modify: `kafka/processor/model/KafkaSourceDescriptor.java` and `KafkaSinkDescriptor.java`.
  Replace `TypeElement` → `ClassDecl`, `ExecutableElement` → `MethodDecl`, `TypeMirror` →
  `TypeRef`.
- Modify: every class under `kafka/processor/validation/` and `kafka/processor/generator/`.
  Each takes a `ProcessingEnv` (or just `Diagnostics` / `TypeOracle`) instead of
  `ProcessingEnvironment`.
- Create: `kafka/processor/KafkaPipeline.java` with
  `void process(ProcessingEnv env)` (collect + validate) and
  `void finish(ProcessingEnv env)` (generate). It holds the cross-round state now in
  `KafkaAnnotationProcessor`.
- Modify: `KafkaAnnotationProcessor.java`. It becomes a thin javac entry: it builds
  `JavacProcessingEnv.of(...)` each round, calls `pipeline.process`, and calls
  `pipeline.finish` when `processingOver()`.
- Create: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/FrontendBoundaryTest.java`.

**Interfaces:**
- Consumes: everything Task 2 produces.
- Produces: `KafkaPipeline(ProcessingEnv)` lifecycle: `process` is called once per round and
  `finish` once at the end. Task 8 reuses it from KSP.

- [ ] **Step 1:** Write `FrontendBoundaryTest.mainSourcesImportNoJavacTypesOutsideEntry`. It
  walks `src/main/java` (resolved from `System.getProperty("user.dir")`) and asserts that no
  file except `KafkaAnnotationProcessor.java` contains `import javax.lang.model` or
  `import javax.annotation.processing`.
- [ ] **Step 2:** Run it. Expected: FAIL, listing the 8 files.
- [ ] **Step 3:** Port the code. Read annotation members through `AnnotationRef` by name; drop
  `getAnnotation(Class)` (4 uses in `RequiredSiblingValidator`, 3 in
  `KafkaAnnotationProcessor`, 1 in `SingletonBridgeValidator`).
- [ ] **Step 4:** Run `mvn test -pl tiko-kafka-processor -am`. Expected: all 9 existing test
  classes plus `FrontendBoundaryTest` pass. Run `08_kafka_order_warehouse` with `mvn exec:java`,
  log to a file, and expect exit 0.
- [ ] **Step 5:** Note anything the model lacked in the PR body, and fix the core API in this
  PR. This is the pilot's purpose.
- [ ] **Step 6:** Commit `refactor(kafka-processor): run on the neutral processor model`.

---

### Task 4: DI processor: diagnostics, output and classpath through core

The first DI slice is IO only. Models still hold javac types after this task.

**Files:**
- Modify: `util/ErrorReporter.java`. Every `Element` parameter becomes `Location`. The
  constructor becomes `ErrorReporter(Diagnostics, Consumer<WiringError>)`. The message
  formatting is unchanged (`DiagnosticsContractTest` guards it).
- Modify: `util/ProcessorContext.java`. Constructor `ProcessorContext(ProcessingEnv env,
  List<String> activeProfiles)`. Remove `getProcessingEnv/getElementUtils/getTypeUtils/getFiler/getMessager`
  and add `env()`. Interim javac access goes through a package-private
  `JavacBridge.elements(env)` that Task 7 deletes.
- Modify: `topology/*Writer.java`, `config/ConfigManifestWriter.java`,
  `config/ConfigBinderServicesWriter.java`. Write through `CodeOutput.writeResource`.
- Modify: `TikoAnnotationProcessor.java:458` and `:1352`. Use `ClasspathProbe.readResource`.
- Modify: `TikoAnnotationProcessor.java:215`. Use `StackTraces.format`.

**Interfaces:**
- Produces: `ErrorReporter.error(String kind, Location at, String problem, String... fixes)`
  and the same `Location`-taking overloads for every existing method (`missingDependency`,
  `circularDependency`, `scopeViolation`, `missingInterface`, ...).

- [ ] **Step 1:** Run the full `tiko-processor` suite and save the passing count to a log
  (baseline).
- [ ] **Step 2:** Make the changes.
- [ ] **Step 3:** Run `mvn test -pl tiko-processor -am`. Expected: the same passing count as
  the baseline, and `DiagnosticsContractTest`, `WiringErrorsWriterTest` and
  `TopologyWriterTest` unchanged.
- [ ] **Step 4:** Commit `refactor(processor): route diagnostics and generated resources through core`.

---

### Task 5: DI processor: event handler and configuration models on core

**Files:**
- Modify: `model/EventHandlerModel.java`, `model/EventTriggerModel.java`,
  `config/ConfigurationModel.java`, `config/ConfigFieldModel.java`. Replace javac types with
  `ClassDecl` / `MethodDecl` / `TypeRef`.
- Create: `collect/EventHandlerCollector.java`. Extract it from `TikoAnnotationProcessor`
  (`collectEventHandlers` :893, `validateRetries` :976, `collectEventTriggers` :1084, timeout
  checks).
- Modify: `config/ConfigurationCollector.java`, `config/ConfigurationValidator.java`,
  `config/ConfigSupportedTypes.java`, `config/ConfigBinderGenerator.java`,
  `generator/EventRegistryGenerator.java`, `topology/ConfigSchemaWriter.java`,
  `topology/JsonSchemaTypeMapper.java`.

**Interfaces:**
- Produces: `EventHandlerCollector.collect(ProcessingEnv env, ProcessorContext ctx) : void`.
  It registers `EventHandlerModel`s exactly as today. `EventHandlerModel.getMethod() : MethodDecl`.
  `EventHandlerModel.getOwner() : ClassDecl`. Generated dispatch calls use
  `getMethod().jvmName()`.

- [ ] **Step 1:** Port the code. Use `jvmName()` wherever generated code invokes a user method.
- [ ] **Step 2:** Run `mvn test -pl tiko-processor -am`. Expected: baseline count; the
  `EventRegistry*`, `EventHandlerRetry/Timeout`, `config/*` and `topology/ConfigSchema*`
  suites unchanged.
- [ ] **Step 3:** Run `mvn exec:java` for `02_config`, `03_events` and `16_async_scope`, logged,
  each exiting 0.
- [ ] **Step 4:** Commit `refactor(processor): event handler and configuration models on core`.

---

### Task 6: DI processor: dependency, factory and component models on core

The big slice. The models, their collectors and every validator and generator that reads them
change together, because they don't compile apart.

**Files:**
- Modify: `model/DependencyModel.java`, `model/FactoryMethodModel.java`,
  `model/ComponentModel.java`, `model/WiringError.java` (if it holds an `Element`).
- Create: `collect/ComponentCollector.java` (from `collectComponents` :251 and
  `collectTestComponents` :276–316), `collect/FactoryMethodCollector.java` (from
  `collectFactoryMethods` :788, `isSelfStaticFactory` :835), `collect/DependencyResolver.java`
  (parameter → `DependencyModel`, `@Named`, `Provider<T>`, `Picker<T>`, `@Pick`).
- Modify: every file in `validation/`, `generator/ComponentFactoryGenerator.java`,
  `generator/ContainerGenerator.java`, `generator/ProxyGenerator.java`,
  `util/AbstractInterfaceMethods.java`, `util/TypeUtil.java`, `topology/TopologyWriter.java`,
  `util/ProcessorContext.java`.

**Interfaces:**
- Produces: `DependencyModel.getType() : TypeRef`, `getUnwrappedType() : TypeRef`,
  `getPickedType() : Optional<TypeRef>`, `getParameter() : ParamDecl`;
  `ComponentModel.getDecl() : ClassDecl` (replaces `getTypeElement()`),
  `getConstructor() : MethodDecl`, `getPostConstructMethods()/getPreDestroyMethods() : List<MethodDecl>`,
  `getImplementedInterface() : Optional<TypeRef>`, `getExposeTypes() : List<TypeRef>`,
  `getStaticFactoryMethod() : Optional<MethodDecl>`;
  `FactoryMethodModel.getMethod() : MethodDecl`, `getReturnType() : TypeRef`.
  Every `Types.isAssignable/isSameType/erasure` call goes through `TypeOracle`, and every
  `Elements.getTypeElement` through `TypeOracle.findClass`.

- [ ] **Step 1:** Port the code. Generators emit `TypeRef.javaPoet()` /
  `ClassDecl.className()` wherever they used `TypeName.get(mirror)` /
  `ClassName.get(typeElement)`, and invoke user methods via `jvmName()`.
- [ ] **Step 2:** Run `mvn test -pl tiko-processor -am`. Expected: the baseline count, and
  `DeterministicGenerationTest` plus every `*Emission*`, `scopes/CrossScopeMatrixTest`,
  `GenericInterfaceRoutingTest` and `Proxy*` test unchanged.
- [ ] **Step 3:** Run `mvn install -DskipTests`, then `mvn exec:java` for every
  `tiko-examples/NN_*` that has a main, logged, each exiting 0. Kafka ones may skip if they need
  Docker; record which ones did.
- [ ] **Step 4:** Commit `refactor(processor): component, factory and dependency models on core`.

---

### Task 7: DI processor: neutral pipeline and javac entry

**Files:**
- Create: `TikoPipeline.java`. It holds the round state (`processed`, `sawTikoAnnotations`),
  the collectors from Tasks 5–6, `validate()` (:1120), `generate()` (:1200),
  `topologyBundleEnabled()` (:1297), `markClasspathShadowedTestComponents()` (:431) and main
  descriptor detection (:1352).
- Modify: `TikoAnnotationProcessor.java`. It shrinks to the javac entry: supported
  annotations, options, source version, `JavacProcessingEnv.of(...)` per round,
  `pipeline.process(env)`, and `pipeline.finish(env)` on `processingOver()`.
- Delete: `JavacBridge` (from Task 4).
- Create: `tiko-processor/src/test/java/io/tiko/processor/FrontendBoundaryTest.java`, the same
  shape as Task 3's. The allowed file is `TikoAnnotationProcessor.java`.

**Interfaces:**
- Produces: `TikoPipeline(List<String> activeProfiles)`, `void process(ProcessingEnv env)`,
  `void finish(ProcessingEnv env)`. `finish` is the only place container / registry /
  topology output is written. Task 8 calls the same two methods from KSP.

- [ ] **Step 1:** Write `FrontendBoundaryTest`, run it, and expect FAIL listing the remaining
  files.
- [ ] **Step 2:** Do the extraction.
- [ ] **Step 3:** Run `mvn verify -pl tiko-processor,tiko-kafka-processor,tiko-test,tiko-examples -am`,
  logged, and check `$?`. Expected: green, and `FrontendBoundaryTest` passes in both modules.
- [ ] **Step 4:** Update the CLAUDE.md "Annotation Processing" section in one paragraph:
  pipeline plus frontend entries, logic only in core-model code.
- [ ] **Step 5:** Commit `refactor(processor): frontend-neutral TikoPipeline behind a thin javac entry`.

---

### Task 8: KSP2 adapter and KSP entry points

**Files:**
- Modify: `tiko-processor-core/pom.xml`. Add `com.google.devtools.ksp:symbol-processing-api`
  and `org.jetbrains.kotlin:kotlin-stdlib`, both `provided`. Add
  `dev.zacsweers.kctfork:core` and `:ksp` as test deps. Versions are pinned per Global
  Constraints.
- Create: `core/ksp/KspProcessingEnv.java` (`of(Resolver, CodeGenerator, KSPLogger, Map<String,String> options)`),
  `core/ksp/KspModel.java` (`KSClassDeclaration` / `KSFunctionDeclaration` / `KSType` → model),
  `core/ksp/KspTypeOracle.java`, `core/ksp/KspLocation.java` (wraps `KSNode`). Written in
  Java; convert Kotlin `Sequence`s with `SequencesKt.toList`.
- Create: `tiko-processor/.../ksp/TikoSymbolProcessorProvider.java` and
  `tiko-kafka-processor/.../ksp/KafkaSymbolProcessorProvider.java`, plus
  `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` in both
  modules (next to the existing javac service files).
- Create: `tiko-processor-core/src/test/java/io/tiko/processor/core/ksp/KspCompiler.java`, a
  test helper wrapping kctfork so tests stay readable Java:
  `KspCompiler.compile(SymbolProcessorProvider, SourceFile...) : KspResult` with
  `exitCode()`, `messages()`, `generatedJava(String fqn) : Optional<String>`.
- Test: `core/ksp/KspModelTest.java`,
  `tiko-processor/src/test/.../ksp/KspBootTest.java`, `KspRoundingTest.java`,
  `KspDiagnosticLocationTest.java`;
  `tiko-kafka-processor/src/test/.../ksp/KspKafkaBridgeTest.java`.

**Interfaces:**
- Consumes: `TikoPipeline.process/finish` (Task 7), `KafkaPipeline.process/finish` (Task 3).
- Produces: `KspProcessingEnv.of(...)`. `ClasspathProbe.readResource` always returns
  `Optional.empty()` in K1.
- The mapping rules the tests pin:
  - `TypeRef.nullability()` is `NULLABLE` iff `KSType.isMarkedNullable`. Java-declared types
    (platform) are `NON_NULL`.
  - `MethodDecl.jvmName()` comes from `Resolver.getJvmName`.
  - `isSuspend` comes from the `SUSPEND` modifier.
  - `DeclKind.KOTLIN_OBJECT` / `KOTLIN_COMPANION` come from `ClassKind.OBJECT` + `isCompanionObject`.
  - `Visibility.KOTLIN_INTERNAL` comes from `Visibility.INTERNAL`.
  - `ParamDecl.hasDefaultValue` comes from `KSValueParameter.hasDefault`.
  - `primaryConstructor()` comes from `KSClassDeclaration.primaryConstructor`.
  - `methodsAnnotatedWith` includes top-level functions (`isTopLevel() == true`).
  - Generated Java goes through `CodeGenerator.createNewFile(deps, pkg, name, "java")` with
    `Dependencies(aggregating = true, files...)` for the container / registry and isolating
    dependencies for per-component factories.

- [ ] **Step 1:** Write the tests:
  - `KspModelTest`, parameterized over the mapping rules above, one row each, each with a
    Kotlin snippet;
  - `KspBootTest.kotlinComponentGraphGeneratesContainer`: a Kotlin `@Component` SINGLETON with
    a constructor dependency on another Kotlin component compiles, `TikoContainerImpl` is
    generated, and it compiles in the same kctfork run (`exitCode() == OK`);
  - `KspRoundingTest.componentGeneratedByAnotherProcessorIsWired`: a second test
    `SymbolProcessorProvider` generates a `@Component` Kotlin file in round 1, and the
    generated container contains its factory (Review Focus 1);
  - `KspDiagnosticLocationTest.missingDependencyReportedOnKotlinParameterLine`: a message
    contains `MISSING_DEPENDENCY` content and the `.kt` file name plus the parameter's line
    (Review Focus 4);
  - `KspKafkaBridgeTest.kotlinKafkaSourceGeneratesBootstrap`.
- [ ] **Step 2:** Run `mvn test -pl tiko-processor-core,tiko-processor,tiko-kafka-processor -am`.
  Expected: FAIL (the adapter is missing).
- [ ] **Step 3:** Implement the adapter and the entries. KSP rounding: `process(resolver)`
  creates `KspProcessingEnv` and calls `pipeline.process`. Call `pipeline.finish` in the first
  round in which `resolver.getNewFiles()` contains no Tiko-annotated symbols after at least one
  round saw some. If no round saw any, call it in `finish()`, or skip if the pipeline has
  nothing. The builder may choose a different trigger only if `KspRoundingTest` and
  `KspBootTest` both pass.
- [ ] **Step 4:** Run the tests. Expected: PASS, and every javac suite still at baseline.
- [ ] **Step 5:** Commit `feat(processor): run Tiko processors on KSP2`.

---

### Task 9: Kotlin construct rules and nullability

**Files:**
- Create: `tiko-processor/.../validation/KotlinConstructValidator.java`. It runs for every
  collected decl. It doesn't branch on frontend; it branches on model facts (`kind()`,
  `isTopLevel()`, `isSuspend()`).
- Create: `tiko-processor/.../validation/NullabilityValidator.java`.
- Modify: `collect/ComponentCollector.java`. For a Kotlin class with `primaryConstructor()`
  present and no `@Inject`-marked constructor, inject the primary constructor with **all**
  parameters. `hasDefaultValue` is ignored for resolution.
- Modify: `generator/ContainerGenerator.java`. When, and only when, some active provider has
  `returnType().nullability() == NULLABLE`, emit null-safe caching for that provider's
  SINGLETON / EVENT slot (a sentinel or holder, not `Map.computeIfAbsent`). Otherwise emit
  byte-identical code to today.
- Modify: `util/ErrorReporter.java`. Add kinds `KOTLIN_UNSUPPORTED = "KOTLIN_UNSUPPORTED"` and
  `NULLABILITY_MISMATCH = "NULLABILITY_MISMATCH"`.
- Test (all KSP via `KspCompiler`, in `tiko-processor/src/test/.../ksp/`):
  `KotlinConstructRulesTest`, `NullabilityRulesTest`, `KotlinInternalMemberTest`,
  `NullableProducerCachingTest`; javac: `JavaOutputUnchangedByNullabilityTest`.

**Interfaces:**
- Consumes: the model facts from Task 8.
- Produces: the error kinds above. Message texts are fixed by the tests below; each includes
  at least one suggested fix.

- [ ] **Step 1:** Write `KotlinConstructRulesTest`, a parameterized test with rows
  `(name, kotlinSource, expectedOk, expectedMessageFragment)`:
  - `finalClassComponentIsSupported` → OK
  - `fieldUseSiteNamedIsSupported` (`@param:Named("x")` on a constructor property) → OK, resolves the named bean
  - `internalComponentIsSupported` → OK
  - `defaultParameterStillInjected`: `class A(val b: B = B())` with `B` a component → OK, and the
    generated factory passes the container's `B` (assert on generated Java)
  - `topLevelProducesRejected` → `KOTLIN_UNSUPPORTED`, the fragment
    `"@Produces on a top-level function"`, plus a fix mentioning "move it into a @Component class"
  - `objectComponentRejected` → `"object declarations can't be components"`, fix "use a class with scope = SINGLETON"
  - `companionProducesRejected` → `"@Produces inside a companion object"`, fix "move it to the enclosing @Component class"
  - `suspendEventHandlerRejectedUntilK2` → `"suspend @EventHandler is not supported yet"`, fix
    "make it a regular fun, or wait for coroutine support (K2)"
- [ ] **Step 2:** Write `NullabilityRulesTest` with rows:
  - `nullableProducerIntoNullablePoint` → OK
  - `nullableProducerIntoNonNullPoint` → `NULLABILITY_MISMATCH`, names the producer method, fix "declare the parameter as `Foo?`"
  - `nonNullProducerIntoNullablePoint` → `NULLABILITY_MISMATCH`, fix "declare the parameter as `Foo`, not `Foo?`"
  - `nullablePointWithoutProviderIsMissingDependency` → `MISSING_DEPENDENCY` (not optional)
  - `javaComponentIntoNullableKotlinPoint` → `NULLABILITY_MISMATCH`
  - `kotlinNullableProducerIntoJavaPoint` (a Java source in the same KSP compile) → `NULLABILITY_MISMATCH`, fix "make the producer non-null"
- [ ] **Step 3:** Write `KotlinInternalMemberTest.internalPostConstructAndHandlerCalledByJvmName`:
  an `internal` `@PostConstruct` and `internal` `@EventHandler` on a Kotlin component compile
  and run (kctfork `exitCode() == OK`, then load the class and boot `Tiko.create()` from the
  compiled classloader, asserting both were invoked through a recorded side effect) (Review
  Focus 2).
- [ ] **Step 4:** Write `NullableProducerCachingTest.singletonNullProducerInvokedOnce`: a
  SINGLETON `@Produces fun foo(): Foo? { calls++; return null }` injected into two components
  gives `calls == 1` and both receive null (Review Focus 3). Write
  `JavaOutputUnchangedByNullabilityTest`: the `DeterministicGenerationTest` fixture generates
  identical text before and after (compare against a checked-in golden file).
- [ ] **Step 5:** Run them. Expected: FAIL.
- [ ] **Step 6:** Implement the validators, the collector rule and the generator change.
- [ ] **Step 7:** Run the KSP suites plus the full `tiko-processor` suite. Expected: PASS and
  baseline.
- [ ] **Step 8:** Commit `feat(processor): Kotlin construct and nullability rules`.

---

### Task 10: Gradle + KSP Kotlin example and CI job

**Files:**
- Create: `tiko-examples-gradle/kotlin-quickstart/` with `settings.gradle.kts`,
  `build.gradle.kts` (plugins `org.jetbrains.kotlin.jvm` and `com.google.devtools.ksp`, repos
  `mavenLocal()` + `mavenCentral()`, `tikoVersion` read from
  `gradle.properties` and kept equal to the reactor version), `gradle.properties`, the Gradle
  wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`, latest stable Gradle), and
  `src/main/kotlin/io/tiko/examples/kotlin/Main.kt` plus the components below.
  `src/test/kotlin/.../QuickstartTest.kt` boots the container with plain JUnit 5 (no
  `@TikoTest`, which is K1.5).
- The example shows:
  - constructor injection between Kotlin classes;
  - an `internal` component;
  - a default parameter;
  - a `@Produces` with a nullable return injected into a `Foo?`;
  - an `@EventHandler` with an `@EventTrigger` chain;
  - a `@Named` with `@param:`;
  - booting via `Tiko.create().use { ... }`.
  `main` exits non-zero if an expected event didn't arrive.
- Modify: `.github/workflows/maven.yml`. Add a job `kotlin-gradle` (JDK 21) that runs
  `mvn -B install -DskipTests`, then `./gradlew build run` in the example directory. A non-zero
  exit fails the job. Pin actions by SHA, as the existing jobs do (#477).
- Modify: `.ai-skills/tiko-release/SKILL.md` and `docs/releasing.md`. The release `sed` /
  `versions:set` step must also bump `tiko-examples-gradle/kotlin-quickstart/gradle.properties`.
  Add it to the same place the archetype template is bumped (`release.yml` ~101–117).

- [ ] **Step 1:** Write `QuickstartTest.containerBootsAndEventChainCompletes`, asserting the
  recorded chain equals `["OrderPlaced", "OrderValidated"]` (names as defined in the example).
- [ ] **Step 2:** Run `mvn install -DskipTests` and then `./gradlew test` in the example.
  Expected: FAIL (no sources yet).
- [ ] **Step 3:** Write the example.
- [ ] **Step 4:** Run `./gradlew build run`, logged, with `$?` = 0. Push to a draft PR and
  confirm the `kotlin-gradle` job is green in CI.
- [ ] **Step 5:** Commit `feat(examples): Gradle + KSP Kotlin quickstart with CI job`.

---

### Task 11: Docs: Kotlin in the build skill, the reference and the site

**Files:**
- Modify: `.ai-skills/tiko-build/SKILL.md` and its mirror under
  `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/`. Add a
  "Kotlin (Gradle + KSP)" section with the setup snippet, the construct rules and the
  nullability rule.
- Create: `.ai-skills/tiko-build/reference/kotlin.md` (and its archetype mirror). It contains:
  - the Gradle snippet, the KSP2 floor, and the supported / rejected construct table with each
    error kind;
  - nullability rules;
  - "`@TikoTest` / `@TestComponent` don't work on KSP yet (planned)";
  - "Maven + Kotlin: works with any Maven plugin that runs a KSP `SymbolProcessorProvider`,
    untested";
  - no kapt mention.
- Modify: `.ai-skills/tiko-build/reference/api-signatures.md`, only if a new public type was
  added. None is expected in K1.
- Modify: `site/`. Add one Kotlin mention, following
  `.ai-skills/tiko-site-maintainer/SKILL.md` (framing rule: no "coming from X" mappings; run its
  verification script).
- Modify: `README.md`. Add a Kotlin line pointing at `reference/kotlin.md`.

- [ ] **Step 1:** Write the docs. Every construct row in `kotlin.md` must have a matching
  test row in `KotlinConstructRulesTest` / `NullabilityRulesTest`; check them by hand and list
  the pairs in the PR body.
- [ ] **Step 2:** Run the `tiko-site-maintainer` verification script. Expected: pass.
- [ ] **Step 3:** Commit `docs(kotlin): Gradle + KSP setup and Kotlin construct rules`.
