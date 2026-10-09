package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A lifecycle hook, event handler or {@code @Inject} constructor that isn't {@code public} is
 * reported on the user's member, not as a javac access error inside generated code (#464).
 */
class NonPublicMemberTest {

    private static final JavaFileObject PING =
            JavaFileObjects.forSourceLines("demo.Ping", "package demo;", "public record Ping() {}");

    /** Line 8 of every fixture is the annotated member. */
    static Stream<Arguments> nonPublicMembers() {
        return Stream.of(
                member("private @PostConstruct", "    @PostConstruct", "    private void hook() {}", "hook"),
                member("package-private @PostConstruct", "    @PostConstruct", "    void hook() {}", "hook"),
                member("protected @PostConstruct", "    @PostConstruct", "    protected void hook() {}", "hook"),
                member("private @PreDestroy", "    @PreDestroy", "    private void close() {}", "close"),
                member("package-private @PreDestroy", "    @PreDestroy", "    void close() {}", "close"),
                member("protected @PreDestroy", "    @PreDestroy", "    protected void close() {}", "close"),
                member("private @EventHandler", "    @EventHandler", "    private void on(Ping p) {}", "on"),
                member("package-private @Inject constructor", "    @Inject", "    Svc() {}", "Svc"));
    }

    private static Arguments member(String name, String annotation, String declaration, String memberName) {
        return Arguments.of(name, annotation, declaration, memberName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPublicMembers")
    void nonPublicMemberIsReportedOnTheUsersSource(
            String name, String annotation, String declaration, String memberName) {
        var svc = svc(annotation, declaration);

        Compilation c = compile(svc);

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("must be public").inFile(svc).onLine(8);
        assertThat(c).hadErrorContaining(memberName).inFile(svc).onLine(8);
        assertThat(c.errors())
                .as("no javac access error from generated code")
                .map(d -> d.getMessage(null))
                .noneMatch(m -> m.contains("is not public in") || m.contains("has private access"));
    }

    @Test
    void publicMembersCompile() {
        var svc = JavaFileObjects.forSourceLines(
                "demo.Svc",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Svc {",
                "    @Inject public Svc() {}",
                "    @PostConstruct public void hook() {}",
                "    @PreDestroy public void close() {}",
                "    @EventHandler public void on(Ping p) {}",
                "}");

        assertThat(compile(svc)).succeeded();
    }

    private static JavaFileObject svc(String annotation, String declaration) {
        return JavaFileObjects.forSourceLines(
                "demo.Svc",
                "package demo;", // 1
                "import io.tiko.Scope;", // 2
                "import io.tiko.annotations.*;", // 3
                "@Component(scope = Scope.SINGLETON)", // 4
                "public class Svc {", // 5
                declaration.contains("Svc()") ? "" : "    public Svc() {}", // 6
                annotation, // 7
                declaration, // 8
                "}");
    }

    private static Compilation compile(JavaFileObject svc) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(svc, PING);
    }
}
