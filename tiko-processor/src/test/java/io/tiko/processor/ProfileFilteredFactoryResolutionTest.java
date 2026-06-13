package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins #272: when two {@code @Component} impls of the same interface are profile-keyed and
 * a third {@code @Component} consumer injects that interface, the generated consumer factory
 * must resolve the injected provider against the <em>active</em> profile.
 *
 * <p>Before the fix, {@code ProcessorContext.findComponentOrFactory} walked the full component
 * map without applying {@code isProfileActive(...)}, so the generated factory called
 * {@code container.getProdGreeter()} even under {@code -Atiko.profiles=dev} — the container
 * itself (which <em>does</em> filter) excluded {@code ProdGreeter}, so compilation failed on a
 * missing method.
 */
class ProfileFilteredFactoryResolutionTest {

    private static Compilation compileWithProfile(String profile) {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "demo.Greeter", "package demo;", "public interface Greeter { String greet(); }");
        JavaFileObject dev = JavaFileObjects.forSourceLines(
                "demo.DevGreeter",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, profiles = {\"dev\"})",
                "public class DevGreeter implements Greeter { public String greet() { return \"dev\"; } }");
        JavaFileObject prod = JavaFileObjects.forSourceLines(
                "demo.ProdGreeter",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, profiles = {\"prod\"})",
                "public class ProdGreeter implements Greeter { public String greet() { return \"prod\"; } }");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(Greeter g) {}",
                "}");
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.profiles=" + profile)
                .compile(iface, dev, prod, consumer);
    }

    @Test
    void consumerFactoryResolvesToActiveProfileImplUnderDev() throws Exception {
        Compilation c = compileWithProfile("dev");
        assertThat(c).succeeded();

        JavaFileObject factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("ConsumerFactory"))
                .findFirst()
                .orElseThrow();
        String content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertThat(content).contains("getDevGreeter").doesNotContain("getProdGreeter");
    }

    @Test
    void consumerFactoryResolvesToActiveProfileImplUnderProd() throws Exception {
        Compilation c = compileWithProfile("prod");
        assertThat(c).succeeded();

        JavaFileObject factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("ConsumerFactory"))
                .findFirst()
                .orElseThrow();
        String content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertThat(content).contains("getProdGreeter").doesNotContain("getDevGreeter");
    }

    /**
     * Direct-class injection of a profile-inactive component exercises the exact-match
     * guards in {@code findComponentOrFactory}. Without those guards, the generator would
     * still emit a {@code container.getDevOnly()} call against a container that excludes
     * {@code DevOnly}; the build would fail on a missing-method diagnostic in generated
     * source rather than a clean unresolvable-dependency error.
     */
    @Test
    void injectingProfileInactiveConcreteClassFailsWithCleanUnresolvableError() {
        JavaFileObject devOnly = JavaFileObjects.forSourceLines(
                "demo.DevOnly",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, profiles = {\"dev\"})",
                "public class DevOnly { public String label() { return \"dev\"; } }");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(DevOnly d) {}",
                "}");
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.profiles=prod")
                .compile(devOnly, consumer);
        assertThat(c).failed();
        // The diagnostic must come from the validator (clean message about a missing
        // provider), not from javac choking on generated source that references a
        // method the container doesn't declare.
        Assertions.assertThat(c.errors())
                .anyMatch(d -> d.getMessage(null).contains("DevOnly"))
                .noneMatch(d -> d.getMessage(null).contains("cannot find symbol"));
    }

    /**
     * #275: two {@code @Produces} methods returning the same type under disjoint profiles
     * coexist at registration; the active one wins at consumer-side resolution. Previously
     * the registration-side dedup rejected both, masking the profile design.
     */
    @Test
    void profileDisjointProduceMethodsResolveToActiveVariant() throws Exception {
        JavaFileObject conn =
                JavaFileObjects.forSourceLines("demo.Conn", "package demo;", "public interface Conn { String url(); }");
        JavaFileObject factories = JavaFileObjects.forSourceLines(
                "demo.ConnFactories",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class ConnFactories {",
                "    @Produces(scope = Scope.SINGLETON, profiles = {\"dev\"})",
                "    public Conn devConn() { return () -> \"jdbc:h2:mem:\"; }",
                "    @Produces(scope = Scope.SINGLETON, profiles = {\"prod\"})",
                "    public Conn prodConn() { return () -> \"jdbc:postgres:prod\"; }",
                "}");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(Conn c) {}",
                "}");
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.profiles=dev")
                .compile(conn, factories, consumer);
        assertThat(c).succeeded();

        JavaFileObject factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("ConsumerFactory"))
                .findFirst()
                .orElseThrow();
        String content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Factory identifier format is <DeclaringClass>_<methodName>.
        Assertions.assertThat(content)
                .contains("produce_ConnFactories_devConn")
                .doesNotContain("produce_ConnFactories_prodConn");
    }

    /**
     * #275 reverse: profile-overlapping {@code @Produces} duplicates still error at registration.
     * A method with no profiles (always-active) overlaps with any other → conflict.
     */
    @Test
    void profileOverlappingProduceMethodsStillError() {
        JavaFileObject conn =
                JavaFileObjects.forSourceLines("demo.Conn", "package demo;", "public interface Conn { String url(); }");
        JavaFileObject factories = JavaFileObjects.forSourceLines(
                "demo.ConnFactories",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class ConnFactories {",
                "    @Produces(scope = Scope.SINGLETON)",
                "    public Conn alwaysActive() { return () -> \"a\"; }",
                "    @Produces(scope = Scope.SINGLETON, profiles = {\"dev\"})",
                "    public Conn dev() { return () -> \"d\"; }",
                "}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(conn, factories);
        assertThat(c).failed();
        Assertions.assertThat(c.errors()).anyMatch(d -> d.getMessage(null).contains("overlapping profiles"));
    }

    /**
     * {@code @Pick} of a profile-inactive component exercises the guard in
     * {@link io.tiko.processor.util.ProcessorContext#findByImplClass}. Same shape as the
     * direct-class injection test above but targeting the {@code @Pick} lookup path used
     * by both the factory and container generators.
     */
    @Test
    void pickingProfileInactiveImplFailsWithCleanError() {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "demo.Tagger", "package demo;", "public interface Tagger { String tag(); }");
        JavaFileObject devTagger = JavaFileObjects.forSourceLines(
                "demo.DevTagger",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, profiles = {\"dev\"})",
                "public class DevTagger implements Tagger { public String tag() { return \"dev\"; } }");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "import io.tiko.annotations.Pick;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(@Pick(DevTagger.class) Tagger t) {}",
                "}");
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.profiles=prod")
                .compile(iface, devTagger, consumer);
        assertThat(c).failed();
        Assertions.assertThat(c.errors()).noneMatch(d -> d.getMessage(null).contains("cannot find symbol"));
    }
}
