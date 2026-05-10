package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Validator tests for {@code Picker<T>} injection sites.
 *
 * <p>{@link PickerValidator} only fires a warning (not an error) when a {@code Picker<T>}
 * has zero local impls — cross-module pickers are legitimate and the runtime aggregator
 * may supply impls from another module.
 */
class PickerValidatorTest {

    @Test
    void picker_with_at_least_one_impl_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(api("Greeter"), impl("English"), consumer("ConsumerService"));
        assertThat(compilation).succeeded();
    }

    @Test
    void picker_with_no_impls_warns_but_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(api("Greeter"), consumer("ConsumerService"));
        // Compilation succeeds — Picker<Greeter> with no local impls is allowed (cross-module case)
        assertThat(compilation).succeeded();
        assertThat(compilation).hadWarningContaining("Picker<Greeter> has no impls registered in this module");
    }

    private static JavaFileObject api(String simpleName) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.picker." + simpleName,
                "package io.tiko.processor.fixtures.picker;",
                "",
                "public interface " + simpleName + " {",
                "    String greet();",
                "}");
    }

    private static JavaFileObject impl(String simpleName) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.picker." + simpleName,
                "package io.tiko.processor.fixtures.picker;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + simpleName + " implements Greeter {",
                "    public String greet() { return \"" + simpleName + "\"; }",
                "}");
    }

    private static JavaFileObject consumer(String simpleName) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.picker." + simpleName,
                "package io.tiko.processor.fixtures.picker;",
                "",
                "import io.tiko.Picker;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + simpleName + " {",
                "    @Inject",
                "    public " + simpleName + "(Picker<Greeter> greeters) {}",
                "}");
    }
}
