package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Drives the generators across the {@code Provider}, {@code Picker}, {@code @Pick}, and
 * named-component dependency shapes in one compilation (#236). These are the branches in
 * {@code ComponentFactoryGenerator} / {@code ContainerGenerator} that unwrap an {@code Optional}
 * (unwrapped generic type, picked type, component name); compiling the fixture runs both
 * generators down those paths.
 */
class GeneratorDependencyShapesTest {

    @Test
    void generatesWiringForProviderPickerPickAndNamedComponent() {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "demo.Greeter", "package demo;", "public interface Greeter { String greet(); }");
        JavaFileObject impl = JavaFileObjects.forSourceLines(
                "demo.PlainGreeter",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PlainGreeter implements Greeter { public String greet() { return \"hi\"; } }");
        // A named component (unrelated type) so the container's named-routing generation runs.
        JavaFileObject tagged = JavaFileObjects.forSourceLines(
                "demo.Tagged",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(name = \"tag\", scope = Scope.SINGLETON)",
                "public class Tagged {}");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Picker;",
                "import io.tiko.Provider;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "import io.tiko.annotations.Pick;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(",
                "            Provider<Greeter> provider,",
                "            Picker<Greeter> picker,",
                "            @Pick(PlainGreeter.class) Greeter picked) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, tagged, consumer);

        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("io.tiko.generated.ConsumerFactory");
    }
}
