package io.tiko.processor.validation;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Two active providers claiming the same (type, name) pair must fail compilation (#332):
 * their generated {@code get(Class, String)} dispatch arms have identical predicates, so
 * the winner was decided by emission order — silently, and changeable by adding an
 * unrelated component. A qualifier exists to be unique per type; duplicates are an error
 * regardless of whether a compile-time consumer exists (runtime {@code get(type, name)}
 * and {@code pick()} lookups are invisible to the processor). Profile-disjoint variants
 * stay legal: the check runs on profile-active providers only, like the unnamed
 * ambiguity check.
 */
class DuplicateNamedProviderTest {

    private static JavaFileObject greeter() {
        return JavaFileObjects.forSourceLines("demo.Greeter", "package demo;", "public interface Greeter {}");
    }

    private static JavaFileObject named(String className, String iface, String name, String... profiles) {
        String profilesAttr = profiles.length == 0
                ? ""
                : ", profiles = {"
                        + String.join(
                                ", ",
                                java.util.Arrays.stream(profiles)
                                        .map(p -> "\"" + p + "\"")
                                        .toList())
                        + "}";
        return JavaFileObjects.forSourceLines(
                "demo." + className,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, name = \"" + name + "\"" + profilesAttr + ")",
                "public class " + className + " implements " + iface + " {}");
    }

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    @Test
    void twoComponentsWithSameInterfaceAndNameFailWithDiagnostic() {
        Compilation c = compile(greeter(), named("G1", "Greeter", "x"), named("G2", "Greeter", "x"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Duplicate qualifier 'x'");
        CompilationSubject.assertThat(c).hadErrorContaining("Suggested fixes");
    }

    @Test
    void namedFactoryCollidingWithNamedComponentFails() {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "demo.Factories",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factories {",
                "  @Produces(scope = Scope.SINGLETON, name = \"x\")",
                "  public Greeter greeter() { return null; }",
                "}");
        Compilation c = compile(greeter(), named("G1", "Greeter", "x"), factory);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Duplicate qualifier 'x'");
    }

    @Test
    void sameNameOnDifferentInterfacesCompiles() {
        JavaFileObject other =
                JavaFileObjects.forSourceLines("demo.Speaker", "package demo;", "public interface Speaker {}");
        Compilation c = compile(greeter(), other, named("G1", "Greeter", "x"), named("S1", "Speaker", "x"));

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void profileDisjointNamedVariantsCompileWhenOneProfileIsActive() {
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.profiles=prod")
                .compile(greeter(), named("DevG", "Greeter", "x", "dev"), named("ProdG", "Greeter", "x", "prod"));

        CompilationSubject.assertThat(c).succeeded();
    }
}
