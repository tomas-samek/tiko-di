package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins the shutdown drain barrier fixes from #305.
 *
 * <p>Ordering: every lookup entry point ({@code get(Class)}, {@code get(Class, String)},
 * {@code getAll(Class)}) must register itself in {@code inFlightGets} BEFORE reading the
 * {@code stopped} gate. With check-then-increment, a caller preempted between the two is
 * invisible to the drain loop — {@code shutdown()} can observe zero in-flight calls and
 * tear down singletons while that caller then runs a full lookup against them.
 *
 * <p>Drain wait: bounded by the configured {@code shutdownTimeout} (same knob as the
 * executor drain in Phase 5), parking between polls instead of busy-spinning a core via
 * {@code Thread.onSpinWait()} for a hard-coded 10 seconds.
 */
class ShutdownDrainBarrierEmissionTest {

    @Test
    void lookupEntryPointsRegisterInFlightBeforeReadingStoppedGate() throws IOException {
        String normalized = generateContainerSource().replaceAll("\\s", "");

        String incrementThenGate =
                "inFlightGets.incrementAndGet();try{if(stopped.get()&&!inShutdownThread.get()){thrownewContainerShutDownException();}";

        assertThat(countOccurrences(normalized, incrementThenGate))
                .as("get(Class), get(Class, String) and getAll(Class) each increment inFlightGets "
                        + "before reading the stopped gate, inside the try whose finally decrements")
                .isEqualTo(3);

        assertThat(normalized)
                .as("no entry point may read the stopped gate before registering in inFlightGets")
                .doesNotContain("thrownewContainerShutDownException();}inFlightGets.incrementAndGet()");
    }

    @Test
    void drainWaitIsBoundedByShutdownTimeoutAndParksBetweenPolls() throws IOException {
        String source = generateContainerSource();

        assertThat(source)
                .as("drain deadline derives from the configured shutdownTimeout, not a magic constant")
                .contains("System.nanoTime() + this.shutdownTimeout.toNanos()")
                .doesNotContain("TimeUnit.SECONDS.toNanos(10)");

        assertThat(source)
                .as("drain loop parks between polls instead of busy-spinning a core")
                .contains("LockSupport.parkNanos")
                .doesNotContain("Thread.onSpinWait()");
    }

    private String generateContainerSource() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.MyService",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PreDestroy;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class MyService {",
                "  public MyService() {}",
                "  @PreDestroy public void cleanup() {}",
                "}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String haystack, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
