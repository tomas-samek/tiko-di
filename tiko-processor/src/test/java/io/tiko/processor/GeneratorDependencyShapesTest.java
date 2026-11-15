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
        // The factory name ends in the module hash (#726).
        org.assertj.core.api.Assertions.assertThat(c.generatedSourceFiles())
                .extracting(javax.tools.JavaFileObject::getName)
                .anyMatch(name -> name.contains("/io/tiko/generated/ConsumerFactory_"));
    }

    /**
     * #732: a picker is handed the container's options, through which it finds the application's
     * container. Built over the module's own container alone, it lists only that module.
     */
    @Test
    void pickerIsBuiltWithTheContainersOptions() throws java.io.IOException {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "demo.Greeter", "package demo;", "public interface Greeter { String greet(); }");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Picker;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(Picker<Greeter> picker) {}",
                "}");
        JavaFileObject producer = JavaFileObjects.forSourceLines(
                "demo.Producer",
                "package demo;",
                "import io.tiko.Picker;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Producer {",
                "    @Produces public StringBuilder names(Picker<Greeter> picker) { return new StringBuilder(); }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, consumer, producer);

        assertThat(c).succeeded();
        var generated = new StringBuilder();
        for (JavaFileObject file : c.generatedSourceFiles()) {
            generated.append(file.getCharContent(true).toString().replaceAll("\\s+", " "));
        }
        org.assertj.core.api.Assertions.assertThat(generated.toString())
                .as("the factory builds the constructor's picker")
                .contains("ContainerPicker<>(container, container.options(), Greeter.class)")
                .as("the container builds the @Produces parameter's picker")
                .contains("ContainerPicker<>(this, options, demo.Greeter.class)");
    }
}
