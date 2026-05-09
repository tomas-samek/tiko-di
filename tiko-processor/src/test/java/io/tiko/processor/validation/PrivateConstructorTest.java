package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Issue #7: the processor must not treat parameters of non-@Inject, non-public
 * constructors as injectable dependencies. The selection rule:
 *   1. an @Inject-annotated constructor (any visibility), or
 *   2. the sole public constructor when no @Inject is present.
 * Anything else is ignored.
 */
class PrivateConstructorTest {

    @Test
    void privateConstructorWithParams_ignoredWhenStaticFactoryProvidesInstantiation() {
        Compilation compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(README_PATTERN_FIXTURE);

        assertThat(compilation).succeeded();
    }

    @Test
    void privateConstructorWithParams_andNoFactory_reportsNoUsableConstructor() {
        Compilation compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(PRIVATE_CTOR_ONLY_FIXTURE);

        assertThat(compilation).failed();
        // The error must be about constructor selection, not about an unresolved
        // Connection dependency that was never asked for.
        assertThat(compilation).hadErrorContaining("@Component must have");
        assertThat(compilation).hadErrorCount(1);
    }

    @Test
    void publicAndPrivateConstructors_publicIsPickedWithoutInjectAnnotation() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(PUBLIC_AND_PRIVATE_CTORS_FIXTURE);

        assertThat(compilation).succeeded();
    }

    /** README pattern: private parameterized ctor + static factory on the same @Component class. */
    private static final JavaFileObject README_PATTERN_FIXTURE = JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.privatector.Database",
            "package io.tiko.processor.fixtures.privatector;",
            "",
            "import java.sql.Connection;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Produces;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class Database {",
            "    private final Connection connection;",
            "",
            "    private Database(Connection connection) {",
            "        this.connection = connection;",
            "    }",
            "",
            "    @Produces(scope = Scope.SINGLETON)",
            "    public static Database create() {",
            "        return new Database(null);",
            "    }",
            "}");

    /** Only a parameterized private constructor — no static factory, no @Inject ctor. */
    private static final JavaFileObject PRIVATE_CTOR_ONLY_FIXTURE = JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.privatector.LonelyPrivate",
            "package io.tiko.processor.fixtures.privatector;",
            "",
            "import java.sql.Connection;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class LonelyPrivate {",
            "    private final Connection connection;",
            "",
            "    private LonelyPrivate(Connection connection) {",
            "        this.connection = connection;",
            "    }",
            "}");

    /** A public no-arg ctor and a private parameterized ctor — the public one must be selected. */
    private static final JavaFileObject PUBLIC_AND_PRIVATE_CTORS_FIXTURE = JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.privatector.MixedCtors",
            "package io.tiko.processor.fixtures.privatector;",
            "",
            "import java.sql.Connection;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class MixedCtors {",
            "    public MixedCtors() {",
            "    }",
            "",
            "    private MixedCtors(Connection connection) {",
            "    }",
            "}");
}
