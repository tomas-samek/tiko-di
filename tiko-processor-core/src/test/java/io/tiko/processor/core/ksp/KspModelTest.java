package io.tiko.processor.core.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.ksp.processing.CodeGenerator;
import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSFile;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.Nullability;
import io.tiko.processor.core.model.Visibility;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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
    void mapsKspSymbolsToNeutralModel(String name, SourceFile source, BiConsumer<ProcessingEnv, Recorded> check) {
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
                                        org.assertj.core.groups.Tuple.tuple("hidden", "hidden$main"),
                                        org.assertj.core.groups.Tuple.tuple("named", "renamed"),
                                        org.assertj.core.groups.Tuple.tuple("plain", "plain")))),
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
                                .containsExactlyInAnyOrder(
                                        org.assertj.core.groups.Tuple.tuple("later", true),
                                        org.assertj.core.groups.Tuple.tuple("now", false)))),
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
                                .extracting(parameter -> parameter.hasDefaultValue())
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
                                .containsExactly(
                                        org.assertj.core.groups.Tuple.tuple("topLevel", true, false),
                                        org.assertj.core.groups.Tuple.tuple("member", false, true)))),
                Arguments.of(
                        "displayNameRendersTheMappedJavaType",
                        KspCompiler.kotlin(
                                "Mapped.kt",
                                "package p",
                                "class Mapped(a: String, b: Int, c: Int?, d: List<String>, e: Map<String, Any>)"),
                        check(env -> assertThat(find(env, "p.Mapped")
                                        .constructors()
                                        .get(0)
                                        .parameters())
                                .extracting(parameter -> parameter.type().displayName())
                                .containsExactly(
                                        "java.lang.String",
                                        "int",
                                        "java.lang.Integer",
                                        "java.util.List<java.lang.String>",
                                        "java.util.Map<java.lang.String, java.lang.Object>"))),
                Arguments.of(
                        "generatedJavaUsesAggregatingOrIsolatingDependencies",
                        KspCompiler.kotlin("Origin.kt", "package p", "class Origin"),
                        (BiConsumer<ProcessingEnv, Recorded>) (env, recorded) -> {
                            var origin = find(env, "p.Origin");
                            write(env, "Isolated", List.of(origin), false);
                            write(env, "Aggregated", List.of(origin), true);
                            assertThat(recorded.dependencies)
                                    .extracting(
                                            dependencies -> dependencies.getAggregating(),
                                            dependencies -> dependencies.getOriginatingFiles().stream()
                                                    .map(KSFile::getFileName)
                                                    .toList())
                                    .containsExactly(
                                            org.assertj.core.groups.Tuple.tuple(false, List.of("Origin.kt")),
                                            org.assertj.core.groups.Tuple.tuple(true, List.of("Origin.kt")));
                            assertThat(recorded.files).containsExactly("p.Isolated.java", "p.Aggregated.java");
                        }));
    }

    private static BiConsumer<ProcessingEnv, Recorded> check(java.util.function.Consumer<ProcessingEnv> check) {
        return (env, recorded) -> check.accept(env);
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

    /** What the probe's {@link CodeGenerator} was asked to create. */
    static final class Recorded {
        final List<Dependencies> dependencies = new ArrayList<>();
        final List<String> files = new ArrayList<>();
    }

    /** Runs the check once, in the first round, over a {@link KspProcessingEnv}. */
    private static final class Probe implements SymbolProcessorProvider {

        private final BiConsumer<ProcessingEnv, Recorded> check;
        private boolean ran;
        private Throwable failure;

        Probe(BiConsumer<ProcessingEnv, Recorded> check) {
            this.check = check;
        }

        @Override
        public SymbolProcessor create(SymbolProcessorEnvironment environment) {
            var recorded = new Recorded();
            var generator = new RecordingCodeGenerator(environment.getCodeGenerator(), recorded);
            return new SymbolProcessor() {
                @Override
                public List<KSAnnotated> process(Resolver resolver) {
                    if (!ran) {
                        ran = true;
                        try {
                            var env = KspProcessingEnv.of(
                                    resolver, generator, environment.getLogger(), environment.getOptions());
                            check.accept(env, recorded);
                        } catch (Throwable t) {
                            failure = t;
                        }
                    }
                    return List.of();
                }
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

    /** Records each file request, then delegates to KSP's own generator. */
    private record RecordingCodeGenerator(CodeGenerator delegate, Recorded recorded) implements CodeGenerator {

        @Override
        public OutputStream createNewFile(
                Dependencies dependencies, String packageName, String fileName, String extensionName) {
            recorded.dependencies.add(dependencies);
            recorded.files.add(packageName + "." + fileName + "." + extensionName);
            return delegate.createNewFile(dependencies, packageName, fileName, extensionName);
        }

        @Override
        public OutputStream createNewFileByPath(Dependencies dependencies, String path, String extensionName) {
            recorded.dependencies.add(dependencies);
            recorded.files.add(path + "." + extensionName);
            return delegate.createNewFileByPath(dependencies, path, extensionName);
        }

        @Override
        public void associate(
                List<? extends KSFile> sources, String packageName, String fileName, String extensionName) {
            delegate.associate(sources, packageName, fileName, extensionName);
        }

        @Override
        public void associateByPath(List<? extends KSFile> sources, String path, String extensionName) {
            delegate.associateByPath(sources, path, extensionName);
        }

        @Override
        public void associateWithClasses(
                List<? extends KSClassDeclaration> classes, String packageName, String fileName, String extensionName) {
            delegate.associateWithClasses(classes, packageName, fileName, extensionName);
        }

        @Override
        public void associateWithFunctions(
                List<? extends KSFunctionDeclaration> functions,
                String packageName,
                String fileName,
                String extensionName) {
            delegate.associateWithFunctions(functions, packageName, fileName, extensionName);
        }

        @Override
        public void associateWithProperties(
                List<? extends KSPropertyDeclaration> properties,
                String packageName,
                String fileName,
                String extensionName) {
            delegate.associateWithProperties(properties, packageName, fileName, extensionName);
        }

        @Override
        public Collection<File> getGeneratedFile() {
            return delegate.getGeneratedFile();
        }
    }
}
