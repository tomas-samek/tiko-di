package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
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
