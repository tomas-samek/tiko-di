package io.tiko.processor.core.ksp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.ksp.RecordingCodeGenerator.Request;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.Nullability;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.PropertyAccessorDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Maps real KSP2 symbols through {@link KspProcessingEnv} inside a test-only symbol processor and
 * asserts on the neutral model it produces: one row per mapping rule, each over its own Kotlin
 * (or Java) snippet. The check runs in the first round; a failing assertion is rethrown after the
 * run so it reports directly.
 */
class KspModelTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void mapsKspSymbolsToNeutralModel(String name, SourceFile source, BiConsumer<ProcessingEnv, List<Request>> check) {
        var probe = new Probe(check);

        var result = KspCompiler.compile(List.of(probe), Map.of(), source);

        probe.rethrowFailure();
        assertThat(probe.ran).as("the probe ran").isTrue();
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                Arguments.of(
                        "nullableIffMarkedNullable",
                        KspCompiler.kotlin("Holder.kt", "package p", "class Holder(a: String?, b: String)"),
                        check(env -> {
                            var parameters =
                                    find(env, "p.Holder").constructors().get(0).parameters();
                            assertThat(parameters)
                                    .extracting(parameter -> parameter.type().nullability())
                                    .containsExactly(Nullability.NULLABLE, Nullability.NON_NULL);
                        })),
                Arguments.of(
                        "javaPlatformTypesAreNonNull",
                        KspCompiler.java(
                                "p/Plain.java",
                                "package p;",
                                "public class Plain {",
                                "  public Plain(String s) {}",
                                "}"),
                        check(env -> {
                            var parameter = find(env, "p.Plain")
                                    .constructors()
                                    .get(0)
                                    .parameters()
                                    .get(0);
                            assertThat(parameter.type().nullability()).isEqualTo(Nullability.NON_NULL);
                        })),
                Arguments.of(
                        "javaNullableAnnotatedTypesAreNonNull",
                        KspCompiler.java(
                                "p/Annotated.java",
                                "package p;",
                                "import org.jetbrains.annotations.Nullable;",
                                "public class Annotated {",
                                "  public Annotated(@Nullable String s) {}",
                                "  @Nullable public String value() { return null; }",
                                "}"),
                        check(env -> {
                            var annotated = find(env, "p.Annotated");
                            var parameter =
                                    annotated.constructors().get(0).parameters().get(0);
                            assertThat(parameter.type().nullability()).isEqualTo(Nullability.NON_NULL);
                            assertThat(annotated.methods().get(0).returnType().nullability())
                                    .isEqualTo(Nullability.NON_NULL);
                        })),
                Arguments.of(
                        "jvmNameComesFromResolver",
                        KspCompiler.kotlin(
                                "Names.kt",
                                "package p",
                                "class Names {",
                                "  internal fun hidden() {}",
                                "  @JvmName(\"renamed\") fun named() {}",
                                "  fun plain() {}",
                                "}"),
                        check(env -> assertThat(find(env, "p.Names").methods())
                                .extracting(MethodDecl::name, MethodDecl::jvmName)
                                .contains(
                                        tuple("hidden", "hidden$main"),
                                        tuple("named", "renamed"),
                                        tuple("plain", "plain")))),
                Arguments.of(
                        "suspendComesFromModifier",
                        KspCompiler.kotlin(
                                "Waits.kt",
                                "package p",
                                "class Waits {",
                                "  suspend fun later() {}",
                                "  fun now() {}",
                                "}"),
                        check(env -> assertThat(find(env, "p.Waits").methods())
                                .extracting(MethodDecl::name, MethodDecl::isSuspend)
                                .containsExactlyInAnyOrder(tuple("later", true), tuple("now", false)))),
                Arguments.of(
                        "objectAndCompanionKinds",
                        KspCompiler.kotlin(
                                "Objects.kt",
                                "package p",
                                "object Single",
                                "class Host { companion object }",
                                "class Regular"),
                        check(env -> {
                            assertThat(find(env, "p.Single").kind()).isEqualTo(DeclKind.KOTLIN_OBJECT);
                            assertThat(find(env, "p.Host.Companion").kind()).isEqualTo(DeclKind.KOTLIN_COMPANION);
                            assertThat(find(env, "p.Regular").kind()).isEqualTo(DeclKind.CLASS);
                        })),
                Arguments.of(
                        "internalVisibility",
                        KspCompiler.kotlin("Inside.kt", "package p", "internal class Inside", "class Outside"),
                        check(env -> {
                            assertThat(find(env, "p.Inside").visibility()).isEqualTo(Visibility.KOTLIN_INTERNAL);
                            assertThat(find(env, "p.Outside").visibility()).isEqualTo(Visibility.PUBLIC);
                        })),
                Arguments.of(
                        "defaultArgumentComesFromHasDefault",
                        KspCompiler.kotlin("Defaults.kt", "package p", "class Defaults(a: Int = 1, b: Int)"),
                        check(env -> assertThat(find(env, "p.Defaults")
                                        .constructors()
                                        .get(0)
                                        .parameters())
                                .extracting(ParamDecl::hasDefaultValue)
                                .containsExactly(true, false))),
                Arguments.of(
                        "primaryConstructorComesFromDeclaration",
                        KspCompiler.kotlin(
                                "Ctors.kt",
                                "package p",
                                "class Ctors(val a: String) {",
                                "  constructor() : this(\"\")",
                                "}"),
                        check(env -> {
                            var ctors = find(env, "p.Ctors");
                            var primary = ctors.primaryConstructor().orElseThrow();
                            assertThat(primary.parameters()).hasSize(1);
                            assertThat(primary.isConstructor()).isTrue();
                            assertThat(ctors.constructors()).hasSize(2).contains(primary);
                        })),
                Arguments.of(
                        "methodsAnnotatedWithIncludesTopLevelFunctions",
                        KspCompiler.kotlin(
                                "Marked.kt",
                                "package p",
                                "annotation class Mark",
                                "@Mark fun topLevel() {}",
                                "class Member { @Mark fun member() {} }"),
                        check(env -> assertThat(env.methodsAnnotatedWith("p.Mark"))
                                .extracting(
                                        MethodDecl::name,
                                        MethodDecl::isTopLevel,
                                        method -> method.owner().isPresent())
                                .containsExactly(tuple("topLevel", true, false), tuple("member", false, true)))),
                Arguments.of(
                        "propertyAccessorsAnnotatedWithFindsUseSiteTargets",
                        accessors(),
                        check(env -> assertThat(env.propertyAccessorsAnnotatedWith("p.Mark"))
                                .extracting(PropertyAccessorDecl::propertyName, PropertyAccessorDecl::setter)
                                .containsExactly(tuple("a", false), tuple("b", true)))),
                Arguments.of(
                        "propertyAccessorLocationsNameTheirClass",
                        accessors(),
                        check(env -> assertThat(env.propertyAccessorsAnnotatedWith("p.Mark"))
                                .extracting(accessor -> accessor.location().enclosingClassName())
                                .containsOnly(Optional.of("p.Holder")))),
                Arguments.of(
                        "methodsAnnotatedWithLeavesOutPropertyAccessors",
                        accessors(),
                        check(env -> assertThat(env.methodsAnnotatedWith("p.Mark"))
                                .extracting(MethodDecl::name)
                                .containsExactly("c"))),
                Arguments.of(
                        "displayNameRendersTheMappedJavaType",
                        KspCompiler.kotlin(
                                "Mapped.kt",
                                "package p",
                                "class Mapped(a: String, b: Int, c: Int?, d: List<String>, e: Array<String>, f: IntArray)"),
                        displayNames(
                                "p.Mapped",
                                "java.lang.String",
                                "int",
                                "java.lang.Integer",
                                "java.util.List<java.lang.String>",
                                "java.lang.String[]",
                                "int[]")),
                Arguments.of(
                        "displayNameJoinsTypeArgumentsAsJavacDoes",
                        KspCompiler.kotlin(
                                "Pairs.kt", "package p", "class Pairs(a: Map<String, Any>, b: Map<String, List<Int>>)"),
                        displayNames(
                                "p.Pairs",
                                "java.util.Map<java.lang.String,java.lang.Object>",
                                "java.util.Map<java.lang.String,java.util.List<java.lang.Integer>>")),
                Arguments.of(
                        "displayNameRendersWildcardsAsJavacDoes",
                        KspCompiler.kotlin(
                                "Wild.kt",
                                "package p",
                                "class Wild(a: List<out Number>, b: List<*>, c: Comparable<in String>)"),
                        displayNames(
                                "p.Wild",
                                "java.util.List<? extends java.lang.Number>",
                                "java.util.List<?>",
                                "java.lang.Comparable<? super java.lang.String>")),
                Arguments.of(
                        "displayNameRendersNestedClassTypeArgumentAsJavacDoes",
                        KspCompiler.kotlin(
                                "Outer.kt",
                                "package p",
                                "class Outer { class Inner }",
                                "class Holds(a: List<Outer.Inner>, b: Outer.Inner)"),
                        displayNames("p.Holds", "java.util.List<p.Outer.Inner>", "p.Outer.Inner")),
                Arguments.of(
                        "innerClassOfGenericOuterRendersAsJavacDoes",
                        innerOfGenericOuter(),
                        displayNames("p.Holds", "p.Outer<java.lang.String>.Inner<java.lang.Integer>")),
                Arguments.of(
                        "innerClassOfGenericOuterIsValidJava",
                        innerOfGenericOuter(),
                        check(env -> assertThat(find(env, "p.Holds")
                                        .constructors()
                                        .get(0)
                                        .parameters()
                                        .get(0)
                                        .type()
                                        .javaPoet())
                                .hasToString("p.Outer<java.lang.String>.Inner<java.lang.Integer>"))),
                // Text only: JavaPoet has one form for `? extends Object` and `?`, and DI keys are
                // erased, so `out Any` never reaches a key; javac would print `? extends java.lang.Object`.
                Arguments.of(
                        "outAnyRendersAsUnboundedWildcard",
                        KspCompiler.kotlin("OutAny.kt", "package p", "class Holds(a: List<out Any>)"),
                        displayNames("p.Holds", "java.util.List<?>")),
                Arguments.of(
                        "varargParameterIsItsArrayType",
                        KspCompiler.kotlin(
                                "Varargs.kt",
                                "package p",
                                "class Names(vararg a: String)",
                                "class Numbers(vararg b: Int)",
                                "class Maybes(vararg c: String?)"),
                        check(env -> assertThat(Stream.of("p.Names", "p.Numbers", "p.Maybes")
                                        .map(name -> find(env, name)
                                                .constructors()
                                                .get(0)
                                                .parameters()
                                                .get(0)
                                                .type()))
                                .extracting(TypeRef::displayName, TypeRef::erasedName, TypeRef::nullability)
                                .containsExactly(
                                        tuple("java.lang.String[]", "java.lang.String[]", Nullability.NON_NULL),
                                        tuple("int[]", "int[]", Nullability.NON_NULL),
                                        tuple("java.lang.String[]", "java.lang.String[]", Nullability.NON_NULL)))),
                Arguments.of(
                        "genericClassAsTypeCarriesItsTypeParameters",
                        KspCompiler.kotlin("Box.kt", "package p", "class Box<T, out U : Number>"),
                        check(env -> {
                            var type = find(env, "p.Box").asType();
                            assertThat(type.displayName()).isEqualTo("p.Box<T,U>");
                            assertThat(type.typeArguments())
                                    .extracting(TypeRef::displayName)
                                    .containsExactly("T", "U");
                            assertThat(env.types()
                                            .isSameType(type, find(env, "p.Box").asType()))
                                    .isTrue();
                        })),
                Arguments.of(
                        "allMethodsFollowsJls848",
                        KspCompiler.java(
                                "p/Sub.java",
                                "package p;",
                                "interface Iface {",
                                "  static void ifaceStatic() {}",
                                "  default void inheritedDefault() {}",
                                "  void implemented();",
                                "}",
                                "class Base {",
                                "  private void hidden() {}",
                                "  static void baseStatic() {}",
                                "  public void overridden() {}",
                                "}",
                                "public class Sub extends Base implements Iface {",
                                "  @Override public void overridden() {}",
                                "  @Override public void implemented() {}",
                                "}"),
                        check(env -> {
                            var all = find(env, "p.Sub").allMethods();
                            assertThat(all)
                                    .extracting(MethodDecl::name)
                                    .contains("inheritedDefault", "implemented", "baseStatic", "overridden")
                                    .doesNotContain("hidden", "ifaceStatic")
                                    .doesNotHaveDuplicates();
                            assertThat(all)
                                    .filteredOn(method -> method.name().equals("overridden"))
                                    .extracting(method ->
                                            method.owner().orElseThrow().qualifiedName())
                                    .containsExactly("p.Sub");
                        })),
                Arguments.of(
                        "inheritedGenericMemberEqualsItsDeclaration",
                        KspCompiler.kotlin(
                                "Inherit.kt",
                                "package p",
                                "open class Base<T> { fun take(t: T) {}; fun take(t: T, n: Int) {} }",
                                "class Sub : Base<String>()"),
                        check(env -> {
                            var declared = find(env, "p.Base").methods();
                            var inherited = find(env, "p.Sub").allMethods().stream()
                                    .filter(method -> method.name().equals("take"))
                                    .toList();
                            assertThat(inherited).hasSize(2).containsExactlyInAnyOrderElementsOf(declared);
                        })),
                Arguments.of(
                        "generatedJavaUsesAggregatingOrIsolatingDependencies",
                        KspCompiler.kotlin("Origin.kt", "package p", "class Origin"),
                        (BiConsumer<ProcessingEnv, List<Request>>) (env, requests) -> {
                            var origin = find(env, "p.Origin");
                            write(env, "Isolated", List.of(origin), false);
                            write(env, "Aggregated", List.of(origin), true);
                            assertThat(requests)
                                    .containsExactly(
                                            new Request("p/Isolated.java", false, List.of("Origin.kt")),
                                            new Request("p/Aggregated.java", true, List.of("Origin.kt")));
                        }));
    }

    /** Getter, setter and function use-site targets of one annotation. */
    private static SourceFile accessors() {
        return KspCompiler.kotlin(
                "Accessors.kt",
                "package p",
                "@Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.FUNCTION)",
                "annotation class Mark",
                "class Holder {",
                "  @get:Mark val a: Int get() = 1",
                "  @set:Mark var b: Int = 0",
                "  @Mark fun c() {}",
                "}");
    }

    private static SourceFile innerOfGenericOuter() {
        return KspCompiler.kotlin(
                "Inner.kt",
                "package p",
                "class Outer<B> { inner class Inner<A> }",
                "class Holds(a: Outer<String>.Inner<Int>)");
    }

    private static BiConsumer<ProcessingEnv, List<Request>> check(Consumer<ProcessingEnv> check) {
        return (env, requests) -> check.accept(env);
    }

    /** The display names of the first constructor's parameter types. */
    private static BiConsumer<ProcessingEnv, List<Request>> displayNames(String className, String... expected) {
        return check(
                env -> assertThat(find(env, className).constructors().get(0).parameters())
                        .extracting(parameter -> parameter.type().displayName())
                        .containsExactly(expected));
    }

    private static ClassDecl find(ProcessingEnv env, String qualifiedName) {
        return env.types().findClass(qualifiedName).orElseThrow(() -> new AssertionError("no class " + qualifiedName));
    }

    private static void write(ProcessingEnv env, String name, List<ClassDecl> originating, boolean aggregating) {
        try {
            env.output()
                    .writeJava(
                            JavaFile.builder("p", TypeSpec.classBuilder(name).build())
                                    .build(),
                            originating,
                            aggregating);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs the check once, in the first round, over a {@link KspProcessingEnv}. */
    private static final class Probe implements SymbolProcessorProvider {

        private final BiConsumer<ProcessingEnv, List<Request>> check;
        private boolean ran;
        private Throwable failure;

        Probe(BiConsumer<ProcessingEnv, List<Request>> check) {
            this.check = check;
        }

        @Override
        public SymbolProcessor create(SymbolProcessorEnvironment environment) {
            var requests = new ArrayList<Request>();
            var generator = new RecordingCodeGenerator(environment.getCodeGenerator(), requests);
            return resolver -> {
                if (!ran) {
                    ran = true;
                    try {
                        var env = KspProcessingEnv.of(
                                resolver, generator, environment.getLogger(), environment.getOptions());
                        check.accept(env, requests);
                    } catch (Throwable t) {
                        failure = t;
                    }
                }
                return List.<KSAnnotated>of();
            };
        }

        void rethrowFailure() {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure != null) {
                throw new AssertionError(failure);
            }
        }
    }
}
