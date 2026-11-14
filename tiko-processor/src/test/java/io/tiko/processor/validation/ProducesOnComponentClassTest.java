package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Issue #1: a static @Produces method on a @Component class that returns the
 * same type must be accepted by the validator. The README documents this as
 * the supported way to force factory usage (private constructor + static
 * factory method) while keeping @Component lifecycle tracking.
 */
class ProducesOnComponentClassTest {

    @Test
    void staticProducesReturningSameTypeOnComponentClass_isAccepted() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(STATIC_PRODUCES_SAME_TYPE_FIXTURE);

        assertThat(compilation).succeeded();
    }

    /**
     * #716: the documented shape, a bare {@code @Component} whose self-returning static factory
     * states the scope. The factory's scope is the component's scope: one instance.
     */
    @Test
    void scopeStatedOnTheSelfFactoryIsTheComponentsScope() throws IOException {
        var container = generatedContainer(database("@Component", "@Produces(scope = Scope.SINGLETON)"));

        Assertions.assertThat(container)
                .contains("__getOrCreateSingleton(\"demo.Database\", () -> databaseFactory.create())");
    }

    /** The scope may equally be stated on {@code @Component}, with a bare {@code @Produces}. */
    @Test
    void scopeStatedOnTheComponentStillGovernsABareSelfFactory() throws IOException {
        var container = generatedContainer(database("@Component(scope = Scope.SINGLETON)", "@Produces"));

        Assertions.assertThat(container)
                .contains("__getOrCreateSingleton(\"demo.Database\", () -> databaseFactory.create())");
    }

    /** Two different scopes stated on the two annotations are a located error, never a silent pick. */
    @Test
    void conflictingScopesOnComponentAndSelfFactoryAreRejected() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(database("@Component(scope = Scope.EVENT)", "@Produces(scope = Scope.SINGLETON)"));

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("demo.Database states scope EVENT on @Component and scope SINGLETON on its"
                        + " @Produces factory 'create'");
        assertThat(compilation).hadErrorContaining("State the scope once");
    }

    /** Two different profile lists stated on the two annotations are a located error as well. */
    @Test
    void conflictingProfilesOnComponentAndSelfFactoryAreRejected() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(database("@Component(profiles = {\"dev\"})", "@Produces(profiles = {\"prod\"})"));

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("demo.Database states profiles [dev] on @Component and profiles [prod] on its"
                        + " @Produces factory 'create'");
        assertThat(compilation).hadErrorContaining("State the profiles once");
    }

    /** Profiles stated on the self factory apply to the component, like its scope. */
    @Test
    void profilesStatedOnTheSelfFactoryApplyToTheComponent() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.profiles=prod")
                .compile(
                        database("@Component", "@Produces(scope = Scope.SINGLETON, profiles = {\"dev\"})"),
                        JavaFileObjects.forSourceLines(
                                "demo.Consumer",
                                "package demo;",
                                "import io.tiko.annotations.Component;",
                                "@Component",
                                "public class Consumer {",
                                "    public Consumer(Database database) {}",
                                "}"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Cannot resolve dependency: demo.Database");
    }

    private static JavaFileObject database(String componentAnnotation, String producesAnnotation) {
        return JavaFileObjects.forSourceLines(
                "demo.Database",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                componentAnnotation,
                "public class Database {",
                "    private Database() {}",
                "    " + producesAnnotation,
                "    public static Database create() { return new Database(); }",
                "}");
    }

    private static String generatedContainer(JavaFileObject... sources) throws IOException {
        Compilation compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
        assertThat(compilation).succeeded();
        JavaFileObject container = compilation.generatedSourceFiles().stream()
                .filter(file -> file.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));
        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static final JavaFileObject STATIC_PRODUCES_SAME_TYPE_FIXTURE = JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.producesonsamecomponent.Database",
            "package io.tiko.processor.fixtures.producesonsamecomponent;",
            "",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Produces;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class Database {",
            "",
            "    private Database() {",
            "    }",
            "",
            "    @Produces(scope = Scope.SINGLETON)",
            "    public static Database create() {",
            "        return new Database();",
            "    }",
            "}");
}
