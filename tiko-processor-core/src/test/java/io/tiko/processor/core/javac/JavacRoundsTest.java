package io.tiko.processor.core.javac;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the javac model keeps across processing rounds. javac keeps one symbol per class for the
 * whole compilation but makes its members and annotations afresh every round, so a declaration
 * mapped in the first round and the same declaration mapped again in the second wrap different
 * elements. Each row maps its declarations in round 1, writes a source so that a round 2 follows,
 * maps them again there, and compares; or reports in round 2 on a location from round 1.
 */
class JavacRoundsTest {

    private static final JavaFileObject TWICE = source(
            "p.Twice",
            "package p;",
            "@Deprecated",
            "public class Twice {",
            "    @Deprecated String field;",
            "    public Twice() {}",
            "    @Deprecated void act(@Deprecated String in, int count) {}",
            "}");

    private static final JavaFileObject SPREAD = source(
            "p.Spread",
            "package p;",
            "@Deprecated",
            "@Outer(",
            "        inner = @Inner)",
            "public class Spread {",
            "    @Deprecated",
            "    String field;",
            "    public Spread() {}",
            "    void act(",
            "            @Deprecated",
            "            String in,",
            "            int count) {}",
            "}",
            "@interface Inner {}",
            "@interface Outer { Inner inner(); }");

    /**
     * The declarations of each row, mapped once per round, compare across rounds as they do
     * within one: equal (with equal hash codes) exactly when they are the same declaration.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("declarationRows")
    void declarationsAreEqualAcrossRoundsExactlyWhenTheSame(
            String name, JavaFileObject source, Function<ProcessingEnv, List<Object>> declarations) {
        var processor = new TwoRounds<>(declarations);

        var compilation = compile(processor, source);

        assertThat(compilation.errors()).isEmpty();
        var first = processor.firstRound();
        var second = processor.secondRound();
        assertThat(second).hasSameSizeAs(first).isNotEmpty();
        for (var i = 0; i < first.size(); i++) {
            assertThat(second.get(i)).as("%s in round 2", first.get(i)).isEqualTo(first.get(i));
            assertThat(second.get(i).hashCode())
                    .as("hash of %s in round 2", first.get(i))
                    .isEqualTo(first.get(i).hashCode());
            for (var j = 0; j < first.size(); j++) {
                if (i != j) {
                    assertThat(second.get(j))
                            .as("%s (round 2) against %s (round 1)", second.get(j), first.get(i))
                            .isNotEqualTo(first.get(i));
                }
            }
        }
    }

    static Stream<Arguments> declarationRows() {
        return Stream.of(
                Arguments.of("classAndItsMembers", TWICE, declarations(env -> {
                    var twice = find(env, "p.Twice");
                    var act = method(twice, "act");
                    var field = field(twice, "field");
                    return List.of(
                            twice,
                            twice.constructors().get(0),
                            act,
                            act.parameters().get(0),
                            act.parameters().get(1),
                            field);
                })),
                Arguments.of("annotationsOnEachKindOfDeclaration", TWICE, declarations(env -> {
                    var twice = find(env, "p.Twice");
                    var act = method(twice, "act");
                    return List.of(
                            twice.annotations().get(0),
                            act.annotations().get(0),
                            act.parameters().get(0).annotations().get(0),
                            field(twice, "field").annotations().get(0));
                })),
                Arguments.of(
                        "overloadsStayApart",
                        source(
                                "p.Overloads",
                                "package p;",
                                "public class Overloads {",
                                "    void m(String s) {}",
                                "    void m(int i) {}",
                                "    void m(String[] s, int i) {}",
                                "    <T> void g(T t) {}",
                                "    <T extends Number> void g(T t) {}",
                                "}"),
                        declarations(env -> List.copyOf(find(env, "p.Overloads").methods()))),
                Arguments.of(
                        "inheritedMethod",
                        source(
                                "p.Child",
                                "package p;",
                                "class Base { void inherited() {} }",
                                "public class Child extends Base {}"),
                        declarations(env -> List.of(find(env, "p.Child").allMethods().stream()
                                .filter(m -> m.name().equals("inherited"))
                                .findFirst()
                                .orElseThrow()))),
                Arguments.of(
                        "recordFieldComponentAndAccessorStayApart",
                        source("p.Rec", "package p;", "public record Rec(String x) {}"),
                        declarations(env -> {
                            var rec = find(env, "p.Rec");
                            return List.of(
                                    field(rec, "x"),
                                    rec.recordComponents().get(0),
                                    method(rec, "x"),
                                    rec.constructors().get(0),
                                    rec.constructors().get(0).parameters().get(0));
                        })),
                Arguments.of(
                        "enumConstants",
                        source("p.Color", "package p;", "public enum Color { RED, GREEN }"),
                        declarations(env -> List.copyOf(find(env, "p.Color").fields()))),
                Arguments.of(
                        "repeatedAndNestedAnnotationsStayApart",
                        source(
                                "p.Tagged",
                                "package p;",
                                "import java.lang.annotation.*;",
                                "@Repeatable(Tags.class) @interface Tag { String value(); }",
                                "@interface Tags { Tag[] value(); }",
                                "@interface Inner { String value(); }",
                                "@interface Outer { Inner one(); Inner[] many(); }",
                                "public class Tagged {",
                                "    @Tag(\"a\") @Tag(\"a\") @Tag(\"b\")",
                                "    @Outer(one = @Inner(\"x\"), many = {@Inner(\"x\"), @Inner(\"y\")})",
                                "    void tagged() {}",
                                "}"),
                        declarations(env -> {
                            var tagged = method(find(env, "p.Tagged"), "tagged");
                            var outer = tagged.annotation("p.Outer").orElseThrow();
                            var all = new ArrayList<Object>(tagged.annotations());
                            all.addAll(tagged.annotationsByType("p.Tag"));
                            all.add(outer.value("one").orElseThrow());
                            all.addAll((List<?>) outer.value("many").orElseThrow());
                            return all;
                        })));
    }

    /**
     * A diagnostic reported in round 2 on a location mapped in round 1 lands where it does when
     * reported in round 1: on the same line of the same source.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("locationRows")
    void locationFromAnEarlierRoundReportsOnItsDeclaration(
            String name, Function<ProcessingEnv, Location> location, long line) {
        var processor = new TwoRounds<>(env -> List.of(location.apply(env)));
        processor.reportInSecondRound = true;

        var compilation = compile(processor, SPREAD);

        assertThat(compilation.errors()).singleElement().satisfies(error -> {
            assertThat(error.getMessage(null)).isEqualTo("late");
            assertThat(error.getSource()).isNotNull();
            assertThat(error.getSource().getName()).endsWith("Spread.java");
            assertThat(error.getLineNumber()).isEqualTo(line);
        });
    }

    static Stream<Arguments> locationRows() {
        return Stream.of(
                Arguments.of(
                        "classAnnotation",
                        location(env -> spread(env).annotations().get(0).location()),
                        2L),
                Arguments.of("class", location(env -> spread(env).location()), 5L),
                Arguments.of(
                        "fieldAnnotation",
                        location(env ->
                                field(spread(env), "field").annotations().get(0).location()),
                        6L),
                Arguments.of(
                        "field", location(env -> field(spread(env), "field").location()), 7L),
                Arguments.of(
                        "constructor",
                        location(env -> spread(env).constructors().get(0).location()),
                        8L),
                Arguments.of(
                        "method", location(env -> method(spread(env), "act").location()), 9L),
                Arguments.of(
                        "parameterAnnotation",
                        location(env -> method(spread(env), "act")
                                .parameters()
                                .get(0)
                                .annotations()
                                .get(0)
                                .location()),
                        10L),
                Arguments.of(
                        "parameter",
                        location(env ->
                                method(spread(env), "act").parameters().get(1).location()),
                        12L),
                Arguments.of(
                        "nestedAnnotation",
                        location(env -> ((AnnotationRef) spread(env)
                                        .annotation("p.Outer")
                                        .orElseThrow()
                                        .value("inner")
                                        .orElseThrow())
                                .location()),
                        4L));
    }

    /** The class {@link #SPREAD} declares, each of its declarations and annotations on a line of its own. */
    private static ClassDecl spread(ProcessingEnv env) {
        return find(env, "p.Spread");
    }

    private static Function<ProcessingEnv, List<Object>> declarations(Function<ProcessingEnv, List<Object>> map) {
        return map;
    }

    private static Function<ProcessingEnv, Location> location(Function<ProcessingEnv, Location> map) {
        return map;
    }

    private static Compilation compile(AbstractProcessor processor, JavaFileObject source) {
        var compilation = Compiler.javac().withProcessors(processor).compile(source);
        ((TwoRounds<?>) processor).rethrowFailure();
        return compilation;
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

    /**
     * Maps in round 1, writes {@code gen.Next} so that a round 2 follows, maps again in round 2;
     * or, with {@link #reportInSecondRound}, reports round 1's single location on round 2's env.
     * A failure is held and rethrown after compilation, as in {@link JavacModelTest}.
     */
    private static final class TwoRounds<T> extends AbstractProcessor {

        private final Function<ProcessingEnv, List<T>> map;
        private final List<List<T>> rounds = new ArrayList<>();
        private boolean reportInSecondRound;
        private Throwable failure;

        TwoRounds(Function<ProcessingEnv, List<T>> map) {
            this.map = map;
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
            if (roundEnv.processingOver() || rounds.size() == 2 || failure != null) {
                return false;
            }
            try {
                var env = JavacProcessingEnv.of(processingEnv, roundEnv);
                if (rounds.isEmpty()) {
                    rounds.add(map.apply(env));
                    writeNext();
                } else if (reportInSecondRound) {
                    rounds.add(List.of());
                    env.diagnostics().error((Location) rounds.get(0).get(0), "late");
                } else {
                    rounds.add(map.apply(env));
                }
            } catch (Throwable t) {
                failure = t;
            }
            return false;
        }

        private void writeNext() {
            try (var writer =
                    processingEnv.getFiler().createSourceFile("gen.Next").openWriter()) {
                writer.write("package gen; class Next {}");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        List<T> firstRound() {
            return rounds.get(0);
        }

        List<T> secondRound() {
            return rounds.get(1);
        }

        void rethrowFailure() {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            assertThat(failure).isNull();
            assertThat(rounds).as("both rounds ran").hasSize(2);
        }
    }
}
