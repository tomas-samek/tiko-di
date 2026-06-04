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
        Assertions.assertThat(content).contains("getDevGreeter");
        Assertions.assertThat(content).doesNotContain("getProdGreeter");
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
        Assertions.assertThat(content).contains("getProdGreeter");
        Assertions.assertThat(content).doesNotContain("getDevGreeter");
    }
}
