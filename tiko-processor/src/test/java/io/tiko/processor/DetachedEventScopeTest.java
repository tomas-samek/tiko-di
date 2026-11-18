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

class DetachedEventScopeTest {

    @Test
    void generatedContainerCarriesDetachedEventScopeMethod() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.Simple",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Simple {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("container not generated"));
        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // One chain. First the method async dispatch calls: it stays package-private and hands
        // the unit to the options, which open it in every module of the application (#811).
        // Then the container's own frame: detachment swaps a closed, empty frame in for the
        // caller's, delegates to the scope core with the publish flag its caller passes (#433 —
        // one publisher of the unit's lifecycle pair), and swaps the caller's frame back in
        // finally. The swap itself saves and sets both ThreadLocals, and a carried unit (#677)
        // uses the same one, so there is one implementation of setting a frame aside.
        assertThat(content)
                .contains("implements Container, DetachedUnitFrame, UnitCarrier")
                .contains("void runInDetachedEventScope(Runnable task)")
                .contains("this.options.runInDetachedUnit(this, task)")
                .doesNotContain("public void runInDetachedEventScope")
                .contains("public void runInDetachedFrame(Runnable task, boolean __publishLifecycle)")
                .contains("UnitFrame __previous = swapFrame(UnitFrame.closed())")
                .contains("__runInEventScope(task, __publishLifecycle)")
                .contains("swapFrame(__previous)")
                .contains("public UnitFrame swapFrame(UnitFrame frame)")
                .contains("new UnitFrame(eventScoped.get(), __unitFrameOpen.get())")
                .contains("eventScoped.set(frame.scope())")
                .contains("__unitFrameOpen.set(frame.open())")
                .contains("public void closeFrame()")
                .contains("public CarriedUnit openCarriedUnit(Event<?> origin)")
                .contains("this.options.openCarriedUnit(this, this::__publishGatedUnitLifecycle, origin)");
    }
}
