package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #589: when the collector rejects a component with a located error, nothing that only follows
 * from the missing model is reported on top of it: not a consumer's "Cannot resolve dependency",
 * not an {@code @EventHandler} "outside a component", and not an instance {@code @Produces} whose
 * class "is not registered as a @Component". Each row pairs one way the collector drops a
 * component with one follow-on, and expects the drop's error as the build's only error.
 */
class RejectedComponentCascadeTest {

    private static final JavaFileObject TEST_COMPONENT_ANNO = JavaFileObjects.forSourceLines(
            "io.tiko.test.TestComponent",
            "package io.tiko.test;",
            "import io.tiko.Scope;",
            "import java.lang.annotation.*;",
            "@Retention(RetentionPolicy.SOURCE)",
            "@Target(ElementType.TYPE)",
            "public @interface TestComponent {",
            "    Class<?> value() default Void.class;",
            "    Scope scope() default Scope.SINGLETON;",
            "    String name() default \"\";",
            "}");

    /** Always valid, so validation runs whatever happens to {@code C}. */
    private static final JavaFileObject ANCHOR = JavaFileObjects.forSourceLines(
            "p.Anchor", "package p;", "@io.tiko.annotations.Component", "public class Anchor {}");

    private static final JavaFileObject CONSUMER = JavaFileObjects.forSourceLines(
            "p.Consumer",
            "package p;",
            "@io.tiko.annotations.Component",
            "public class Consumer {",
            "    public Consumer(C c) {}",
            "}");

    @TempDir
    Path project;

    /** A way the collector drops {@code C}; {@code %s} is where the follow-on member goes. */
    private record DropPath(String name, String sourceRoot, String source, String error) {}

    /** What follows from {@code C} being absent: a member of {@code C}, or the {@link #CONSUMER}. */
    private record FollowOn(String name, String member, boolean consumer) {}

    private static final List<DropPath> DROP_PATHS = List.of(
            new DropPath(
                    "noUsableConstructor",
                    "src/main/java",
                    """
                    @Component
                    public class C {
                        public C(String a) {}
                        public C() {}
                        %s
                    }
                    """,
                    "@Component must have an @Inject-annotated constructor or a single public constructor"),
            new DropPath(
                    "multipleInjectConstructors", "src/main/java", """
                    @Component
                    public class C {
                        @Inject public C(String a) {}
                        @Inject public C() {}
                        %s
                    }
                    """, "Multiple constructors annotated with @Inject"),
            new DropPath(
                    "unresolvedConstructorPick",
                    "src/main/java",
                    """
                    @Component
                    public class C {
                        @Inject public C(@Pick(Gen.class) Object dep) {}
                        %s
                    }
                    """,
                    "@Pick on parameter 'dep' of the C constructor: the picked class could not be resolved"),
            new DropPath(
                    "unresolvedStaticFactoryPick",
                    "src/main/java",
                    """
                    @Component
                    public class C {
                        private C() {}
                        @Produces public static C create(@Pick(Gen.class) Object dep) { return new C(); }
                        %s
                    }
                    """,
                    "@Pick on parameter 'dep' of C.create: the picked class could not be resolved"),
            new DropPath(
                    "unresolvedExpose",
                    "src/main/java",
                    """
                    @Component(expose = {Runnable.class, Gen.class})
                    public class C implements Runnable {
                        public void run() {}
                        %s
                    }
                    """,
                    "@Component(expose = {...}) on C: an exposed type could not be resolved"),
            new DropPath(
                    "unresolvedTestComponentValue",
                    "src/test/java",
                    """
                    @io.tiko.test.TestComponent(value = Gen.class)
                    public class C {
                        %s
                    }
                    """,
                    "@TestComponent(value = ...) on p.C: the shadowed type could not be resolved"),
            new DropPath(
                    "testComponentInProductionSources",
                    "src/main/java",
                    """
                    @io.tiko.test.TestComponent
                    public class C {
                        %s
                    }
                    """,
                    "@TestComponent C is in production sources"));

    private static final List<FollowOn> FOLLOW_ONS = List.of(
            new FollowOn("consumer", "", true),
            new FollowOn("eventHandler", "@EventHandler public void on(String event) {}", false),
            new FollowOn(
                    "instanceProduces",
                    "public static class Widget {} @Produces public Widget widget() { return new Widget(); }",
                    false));

    static Stream<Arguments> rows() {
        return DROP_PATHS.stream()
                .flatMap(drop -> FOLLOW_ONS.stream()
                        .map(followOn -> Arguments.of(drop.name() + " x " + followOn.name(), drop, followOn)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void rejectedComponentReportsOnlyItsOwnError(String name, DropPath drop, FollowOn followOn) throws IOException {
        var sources = new ArrayList<>(List.of(TEST_COMPONENT_ANNO, ANCHOR, sourceOfC(drop, followOn)));
        if (followOn.consumer()) {
            sources.add(CONSUMER);
        }

        Compilation compilation = Compiler.javac()
                .withProcessors(new GenGenerator(), new TikoAnnotationProcessor())
                .compile(sources);

        assertThat(compilation.errors())
                .extracting(error -> error.getMessage(Locale.ROOT))
                .singleElement()
                .asString()
                .contains(drop.error());
    }

    private JavaFileObject sourceOfC(DropPath drop, FollowOn followOn) throws IOException {
        var file = project.resolve(drop.sourceRoot()).resolve("p").resolve("C.java");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                "package p;\nimport io.tiko.annotations.*;\n" + drop.source().formatted(followOn.member()));
        return JavaFileObjects.forResource(file.toUri().toURL());
    }
}
