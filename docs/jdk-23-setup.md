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
                <groupId>io.tiko</groupId>
                <artifactId>tiko-processor</artifactId>
                <version>0.1.0</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

## Gradle

```groovy
dependencies {
    implementation "io.tiko:tiko-api:0.1.0"
    implementation "io.tiko:tiko-runtime:0.1.0"
    annotationProcessor "io.tiko:tiko-processor:0.1.0"
}
```

The `annotationProcessor` configuration sets `--processor-path` for you, which is sufficient for JDK 23+.

## Plain `javac`

```bash
javac -proc:full \
      --processor-path tiko-processor-0.1.0.jar \
      -cp tiko-api-0.1.0.jar:tiko-runtime-0.1.0.jar \
      -d out \
      src/main/java/com/example/*.java
```

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
