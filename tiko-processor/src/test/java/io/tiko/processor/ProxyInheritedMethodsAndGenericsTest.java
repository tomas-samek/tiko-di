package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for two related proxy-generation gaps surfaced by the
 * persistence cookbook ({@code tiko-examples/10_persistence_jdbc}).
 *
 * <p>1. <b>Inherited methods.</b> The earlier proxy generator only walked the
 * declared methods of the target interface. {@code java.sql.Connection extends
 * Wrapper}, so {@code unwrap} and {@code isWrapperFor} live on the parent —
 * not declared on {@code Connection} itself. The generated proxy omitted them
 * and failed javac's abstract-method check.
 *
 * <p>2. <b>Method type parameters.</b> {@code Wrapper.unwrap} is
 * {@code <T> T unwrap(Class<T>)}. Without carrying the type parameter onto the
 * generated method, {@code T} appears unbound in the proxy.
 *
 * <p>This test pins both fixes for both proxy code paths:
 * {@code @Component}-class proxies and {@code @Produces}-output proxies.
 */
class ProxyInheritedMethodsAndGenericsTest {

    @Test
    void componentProxyIncludesInheritedAndGenericMethods() throws IOException {
        JavaFileObject parent = JavaFileObjects.forSourceLines(
                "io.example.Wrapping",
                "package io.example;",
                "public interface Wrapping {",
                "  <T> T unwrap(Class<T> type);",
                "  boolean isWrapperFor(Class<?> type);",
                "}");

        JavaFileObject child = JavaFileObjects.forSourceLines(
                "io.example.Resource",
                "package io.example;",
                "public interface Resource extends Wrapping {",
                "  String name();",
                "}");

        JavaFileObject impl = JavaFileObjects.forSourceLines(
                "io.example.ResourceImpl",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.REQUEST)",
                "public class ResourceImpl implements Resource {",
                "  public String name() { return \"r\"; }",
                "  public <T> T unwrap(Class<T> type) { return null; }",
                "  public boolean isWrapperFor(Class<?> type) { return false; }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(parent, child, impl);

        // The whole point: the generated proxy must compile alongside the user's code.
        // Pre-fix, javac fails on ResourceImplProxy.java with
        // "ResourceImplProxy is not abstract and does not override abstract method unwrap(...) in Wrapping".
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject proxy = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("ResourceImplProxy"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ResourceImplProxy not generated"));

        String body = new String(proxy.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Inherited methods present:
        assertThat(body).contains("unwrap");
        assertThat(body).contains("isWrapperFor");

        // Type parameter carried through (T must be a method-level type parameter, not unbound):
        assertThat(body).contains("<T>");
        assertThat(body).containsPattern("(?s)<T>\\s+T\\s+unwrap\\s*\\(\\s*Class<T>");

        // Declared method also present:
        assertThat(body).contains("public String name()");
    }

    @Test
    void factoryOutputProxyIncludesInheritedAndGenericMethods() throws IOException {
        JavaFileObject parent = JavaFileObjects.forSourceLines(
                "io.example.Wrapping",
                "package io.example;",
                "public interface Wrapping {",
                "  <T> T unwrap(Class<T> type);",
                "  boolean isWrapperFor(Class<?> type);",
                "}");

        JavaFileObject child = JavaFileObjects.forSourceLines(
                "io.example.Resource",
                "package io.example;",
                "public interface Resource extends Wrapping {",
                "  String name();",
                "}");

        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.ResourceFactory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class ResourceFactory {",
                "  @Produces(scope = Scope.REQUEST)",
                "  public Resource resource() { return null; }",
                "}");

        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "io.example.ResourceUser",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class ResourceUser {",
                "  @Inject public ResourceUser(Resource r) {}",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(parent, child, factory, consumer);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject proxy = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("ResourceFactory_resourceProxy"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ResourceFactory_resourceProxy not generated"));

        String body = new String(proxy.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(body).contains("unwrap");
        assertThat(body).contains("isWrapperFor");
        assertThat(body).contains("<T>");
        assertThat(body).containsPattern("(?s)<T>\\s+T\\s+unwrap\\s*\\(\\s*Class<T>");
        assertThat(body).contains("public String name()");
    }
}
