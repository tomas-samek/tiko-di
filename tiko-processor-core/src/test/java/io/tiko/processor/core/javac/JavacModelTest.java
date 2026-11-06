package io.tiko.processor.core.javac;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.Nullability;
import io.tiko.processor.core.model.TypeRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Maps real javac elements through {@link JavacProcessingEnv} inside a test-only processor and
 * asserts on the neutral model it produces. Each row compiles one source; the check runs in the
 * first processing round and, where it needs the compiler's verdict, again on the result.
 */
class JavacModelTest {

    private static final String COMPONENT = "io.tiko.annotations.Component";
    private static final String PICK = "io.tiko.annotations.Pick";

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void mapsJavacElementsToNeutralModel(String name, JavaFileObject source, Check check) {
        var processor = new ProbeProcessor(check.inRound());

        var compilation = Compiler.javac().withProcessors(processor).compile(source);

        processor.rethrowFailure();
        check.afterCompile().accept(compilation);
    }

    static Stream<Arguments> rows() {
        var diagnosticSource = source(
                "p.Loud", //
                "package p;",
                "public class Loud {",
                "    void quiet() {}",
                "    void shout() {}",
                "}");
        return Stream.of(
                Arguments.of(
                        "classAnnotationValuesIncludeDefaults",
                        source("p.Foo", "package p;", "@io.tiko.annotations.Component", "public class Foo {}"),
                        Check.model(env -> {
                            var component = env.classesAnnotatedWith(COMPONENT).get(0);
                            var annotation = component.annotation(COMPONENT).orElseThrow();
                            assertThat(annotation.enumName("scope")).isEqualTo("PROTOTYPE");
                        })),
                Arguments.of(
                        "classValueMapsToTypeRef",
                        source(
                                "p.Bar",
                                "package p;",
                                "import io.tiko.annotations.Pick;",
                                "class Foo {}",
                                "public class Bar {",
                                "    public Bar(@Pick(Foo.class) Object foo) {}",
                                "}"),
                        Check.model(env -> {
                            var parameter = find(env, "p.Bar")
                                    .constructors()
                                    .get(0)
                                    .parameters()
                                    .get(0);
                            var pick = parameter.annotation(PICK).orElseThrow();
                            assertThat(pick.types("value").get(0).erasedName()).isEqualTo("p.Foo");
                        })),
                Arguments.of(
                        "javaTypesAreNonNull",
                        source(
                                "p.Plain",
                                "package p;",
                                "public class Plain {",
                                "    String field;",
                                "    String echo(String in) { return in; }",
                                "}"),
                        Check.model(env -> {
                            var plain = find(env, "p.Plain");
                            var echo = method(plain, "echo");
                            assertThat(List.of(
                                            plain.fields().get(0).type(),
                                            echo.returnType(),
                                            echo.parameters().get(0).type()))
                                    .extracting(TypeRef::nullability)
                                    .containsOnly(Nullability.NON_NULL);
                        })),
                Arguments.of(
                        "primitiveErasedName",
                        source("p.Counter", "package p;", "public class Counter {", "    int count;", "}"),
                        Check.model(env -> {
                            var type = find(env, "p.Counter").fields().get(0).type();
                            assertThat(type.erasedName()).isEqualTo("int");
                            assertThat(type.isPrimitive()).isTrue();
                            assertThat(type.declaration()).isEmpty();
                        })),
                Arguments.of(
                        "genericTypeArguments",
                        source(
                                "p.Holder",
                                "package p;",
                                "class Foo {}",
                                "public class Holder {",
                                "    io.tiko.Provider<Foo> foo;",
                                "}"),
                        Check.model(env -> {
                            var type = find(env, "p.Holder").fields().get(0).type();
                            assertThat(type.erasedName()).isEqualTo("io.tiko.Provider");
                            assertThat(type.typeArguments())
                                    .extracting(TypeRef::erasedName)
                                    .containsExactly("p.Foo");
                        })),
                Arguments.of(
                        "jvmNameEqualsNameInJava",
                        source("p.Named", "package p;", "public class Named {", "    void doWork() {}", "}"),
                        Check.model(env -> {
                            var method = method(find(env, "p.Named"), "doWork");
                            assertThat(method.jvmName()).isEqualTo(method.name());
                        })),
                Arguments.of(
                        "javaHasNoPrimaryConstructor",
                        source("p.Built", "package p;", "public class Built {", "    public Built(String a) {}", "}"),
                        Check.model(env -> {
                            var built = find(env, "p.Built");
                            assertThat(built.constructors()).hasSize(1);
                            assertThat(built.primaryConstructor()).isEmpty();
                        })),
                Arguments.of(
                        "isAssignableHonoursGenerics",
                        source(
                                "p.Lists",
                                "package p;",
                                "import java.util.*;",
                                "public class Lists {",
                                "    List<String> strings;",
                                "    List<Object> objects;",
                                "    ArrayList<String> arrayOfStrings;",
                                "}"),
                        Check.model(env -> {
                            var lists = find(env, "p.Lists");
                            var strings = field(lists, "strings").type();
                            var objects = field(lists, "objects").type();
                            var arrayOfStrings = field(lists, "arrayOfStrings").type();
                            assertThat(env.types().isAssignable(strings, objects))
                                    .isFalse();
                            assertThat(env.types().isAssignable(arrayOfStrings, strings))
                                    .isTrue();
                        })),
                Arguments.of(
                        "diagnosticLandsOnElement",
                        diagnosticSource,
                        Check.compiled(
                                env -> {
                                    var shout = method(find(env, "p.Loud"), "shout");
                                    env.diagnostics().error(shout.location(), "neutral diagnostic");
                                    assertThat(env.diagnostics().hasErrors()).isTrue();
                                },
                                compilation -> CompilationSubject.assertThat(compilation)
                                        .hadErrorContaining("neutral diagnostic")
                                        .inFile(diagnosticSource)
                                        .onLine(4))),
                Arguments.of(
                        "missingClasspathResourceIsEmpty",
                        source("p.Lone", "package p;", "public class Lone {}"),
                        Check.model(
                                env -> assertThat(env.classpath().readResource("META-INF/tiko-core-test/absent.txt"))
                                        .isEmpty())),
                Arguments.of(
                        "classpathResourceIsRead",
                        source("p.Lone", "package p;", "public class Lone {}"),
                        Check.model(env -> assertThat(
                                        env.classpath().readResource("META-INF/tiko-core-test/present.txt"))
                                .hasValueSatisfying(text -> assertThat(text).contains("probe-marker")))),
                Arguments.of(
                        "writtenResourceLandsInClassOutput",
                        source("p.Origin", "package p;", "public class Origin {}"),
                        Check.compiled(
                                env -> write(() -> env.output()
                                        .writeResource(
                                                "META-INF/tiko-core-test/out.txt",
                                                "hello\n",
                                                List.of(find(env, "p.Origin")))),
                                compilation -> CompilationSubject.assertThat(compilation)
                                        .generatedFile(
                                                StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko-core-test/out.txt")
                                        .contentsAsUtf8String()
                                        .isEqualTo("hello\n"))),
                Arguments.of(
                        "writtenJavaFileCompiles",
                        source("p.Origin", "package p;", "public class Origin {}"),
                        Check.compiled(
                                env -> write(() -> env.output()
                                        .writeJava(
                                                JavaFile.builder(
                                                                "gen",
                                                                TypeSpec.classBuilder("Made")
                                                                        .build())
                                                        .build(),
                                                List.of(find(env, "p.Origin")),
                                                false)),
                                compilation -> CompilationSubject.assertThat(compilation)
                                        .generatedSourceFile("gen.Made")
                                        .contentsAsUtf8String()
                                        .contains("class Made"))),
                Arguments.of(
                        "declarationsEqualAcrossEnvs",
                        source(
                                "p.Twice",
                                "package p;",
                                "public class Twice {",
                                "    @Deprecated String field;",
                                "    void act(String in) {}",
                                "}"),
                        Check.acrossEnvs((first, second) -> {
                            var fromFirst = declarations(find(first, "p.Twice"));
                            var fromSecond = declarations(find(second, "p.Twice"));
                            assertThat(fromSecond).isEqualTo(fromFirst);
                            assertThat(fromSecond).allMatch(new HashSet<>(fromFirst)::contains);
                        })),
                Arguments.of(
                        "sourceVersionIsCompilationRelease",
                        source("p.Lone", "package p;", "public class Lone {}"),
                        Check.model(env -> assertThat(env.sourceVersion().release())
                                .isEqualTo(SourceVersion.latestSupported().ordinal()))));
    }

    private static JavaFileObject source(String fqn, String... lines) {
        return JavaFileObjects.forSourceLines(fqn, lines);
    }

    private static ClassDecl find(ProcessingEnv env, String fqn) {
        return env.types().findClass(fqn).orElseThrow();
    }

    private static MethodDecl method(ClassDecl owner, String name) {
        return owner.methods().stream()
                .filter(m -> m.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static FieldDecl field(ClassDecl owner, String name) {
        return owner.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** The class, its method and parameter, its field and the field's annotation. */
    private static List<Object> declarations(ClassDecl owner) {
        var act = method(owner, "act");
        var field = field(owner, "field");
        return List.of(
                owner, act, act.parameters().get(0), field, field.annotations().get(0));
    }

    private static void write(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /**
     * What a row checks: {@code inRound} on the mapped model (given the round's env and a supplier
     * of further, independently created envs), {@code afterCompile} on the result.
     */
    private record Check(
            BiConsumer<ProcessingEnv, Supplier<ProcessingEnv>> inRound, Consumer<Compilation> afterCompile) {

        static Check model(Consumer<ProcessingEnv> inRound) {
            return compiled(inRound, Check::noErrors);
        }

        static Check compiled(Consumer<ProcessingEnv> inRound, Consumer<Compilation> afterCompile) {
            return new Check((env, moreEnvs) -> inRound.accept(env), afterCompile);
        }

        /** Checks two {@link JavacProcessingEnv} instances created separately over the same round. */
        static Check acrossEnvs(BiConsumer<ProcessingEnv, ProcessingEnv> inRound) {
            return new Check((env, moreEnvs) -> inRound.accept(env, moreEnvs.get()), Check::noErrors);
        }

        private static void noErrors(Compilation compilation) {
            assertThat(compilation.errors()).isEmpty();
        }
    }

    /**
     * Runs the row's check once, in the first round, over a fresh {@link JavacProcessingEnv}.
     * A failing assertion is held and rethrown after compilation so the test reports it directly
     * instead of as a javac-wrapped processor crash.
     */
    private static final class ProbeProcessor extends AbstractProcessor {

        private final BiConsumer<ProcessingEnv, Supplier<ProcessingEnv>> check;
        private boolean ran;
        private Throwable failure;

        ProbeProcessor(BiConsumer<ProcessingEnv, Supplier<ProcessingEnv>> check) {
            this.check = check;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (ran) {
                return false;
            }
            ran = true;
            try {
                check.accept(
                        JavacProcessingEnv.of(processingEnv, roundEnv),
                        () -> JavacProcessingEnv.of(processingEnv, roundEnv));
            } catch (Throwable t) {
                failure = t;
            }
            return false;
        }

        void rethrowFailure() {
            assertThat(ran).as("the in-round check ran").isTrue();
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            assertThat(failure).isNull();
        }
    }
}
