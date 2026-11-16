package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.util.List;
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

    private static final String SINGLETON_PRODUCES = "@Produces(scope = Scope.SINGLETON)";

    @Test
    void staticProducesReturningSameTypeOnComponentClass_isAccepted() {
        Compilation compilation = compile(database("@Component(scope = Scope.SINGLETON)", SINGLETON_PRODUCES));

        assertThat(compilation).succeeded();
    }

    /**
     * #716: the documented shape, a bare {@code @Component} whose self-returning static factory
     * states the scope. The factory's scope is the component's scope: one instance.
     */
    @Test
    void scopeStatedOnTheSelfFactoryIsTheComponentsScope() throws IOException {
        var container = generatedContainer(database("@Component", SINGLETON_PRODUCES));

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
        Compilation compilation = compile(database("@Component(scope = Scope.EVENT)", SINGLETON_PRODUCES));

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("demo.Database states scope EVENT on @Component and scope SINGLETON on its"
                        + " @Produces factory 'create'");
        assertThat(compilation).hadErrorContaining("State the scope once");
    }

    /** Two different profile lists stated on the two annotations are a located error as well. */
    @Test
    void conflictingProfilesOnComponentAndSelfFactoryAreRejected() {
        Compilation compilation =
                compile(database("@Component(profiles = {\"dev\"})", "@Produces(profiles = {\"prod\"})"));

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("demo.Database states profiles [dev] on @Component and profiles [prod] on its"
                        + " @Produces factory 'create'");
        assertThat(compilation).hadErrorContaining("State the profiles once");
    }

    /** Profiles stated on the self factory apply to the component, like its scope. */
    @Test
    void profilesStatedOnTheSelfFactoryApplyToTheComponent() {
        Compilation compilation = compile(
                List.of("-Atiko.profiles=prod"),
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
        Compilation compilation = compile(sources);
        assertThat(compilation).succeeded();
        return generated(compilation, "TikoContainerImpl");
    }
}
