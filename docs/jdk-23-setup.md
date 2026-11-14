# Annotation processing on JDK 23+

Starting with **JDK 23**, `javac` no longer runs annotation processing implicitly. If none of `-processor`, `--processor-path`, `--processor-module-path`, or `-proc:full`/`-proc:only` is specified, the compiler **silently skips processing** — meaning Tiko's code generator never runs, no `TikoContainerImpl` is produced, and your app fails at runtime with a cryptic `ClassNotFoundException: io.tiko.generated.TikoContainerImpl` (or a `NoSuchElementException` from `Tiko.create()`).

> JDK 21 and 22 still run processing but emit a warning ("`Annotation processing is enabled because one or more processors were found on the class path...`"). JDK 23+ makes the new behavior the default.

## Maven (recommended)

The snippet in the [README installation section](../README.md#installation) is already correct for JDK 23+ — `<annotationProcessorPaths>` passes `--processor-path` to `javac`, which satisfies the explicit-opt-in requirement. **Requires `maven-compiler-plugin` ≥ 3.13.0**; older versions of the plugin do not reliably forward the flag on JDK 23+.

If you are on an older plugin version and cannot upgrade, force processing explicitly:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.11.0</version>
    <configuration>
        <proc>full</proc> <!-- explicit opt-in for JDK 23+ -->
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.tomas-samek</groupId>
                <artifactId>tiko-processor</artifactId>
                <version>0.6.0</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

## Gradle

```groovy
dependencies {
    implementation "io.github.tomas-samek:tiko-api:0.6.0"
    implementation "io.github.tomas-samek:tiko-runtime:0.6.0"
    annotationProcessor "io.github.tomas-samek:tiko-processor:0.6.0"
}
```

The `annotationProcessor` configuration sets `--processor-path` for you, which is sufficient for JDK 23+.

## Plain `javac`

`javac` does not resolve dependencies, so the processor path lists `tiko-processor` together with what it needs to run: `tiko-processor-core`, `tiko-config`, `tiko-api`, SnakeYAML and JavaPoet (`com.palantir.javapoet`). `tiko-processor-core` exists from 0.7.0; leave it out for 0.6.x. Take the SnakeYAML and JavaPoet versions from the `tiko-processor` POM of the release you use.

```bash
V=0.6.0   # the Tiko version
PROCESSOR_PATH=tiko-processor-$V.jar:tiko-processor-core-$V.jar:tiko-config-$V.jar:tiko-api-$V.jar:snakeyaml-2.3.jar:javapoet-0.1.0.jar

javac -proc:full \
      --processor-path "$PROCESSOR_PATH" \
      -cp tiko-api-$V.jar:tiko-runtime-$V.jar \
      -d out \
      src/main/java/com/example/*.java
```

With only `tiko-processor-<ver>.jar` on the processor path, `javac` stops with "Bad service configuration file, or exception thrown while constructing Processor object".

## Verifying processing actually ran

After `mvn compile` (or the equivalent), confirm the generated container exists:

```bash
ls target/generated-sources/annotations/io/tiko/generated/
# Expected:
#   TikoContainerImpl.java
#   <YourComponent>Factory.java   (one per @Component)
#   EventRegistry.java
```

If that directory is empty or missing, processing was skipped — re-check the compiler plugin version and the `<annotationProcessorPaths>` / `annotationProcessor` declaration.
