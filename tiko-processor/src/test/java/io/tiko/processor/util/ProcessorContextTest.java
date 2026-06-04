package io.tiko.processor.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.Scope;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import java.util.List;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Covers logic-heavy methods on {@link ProcessorContext} that the end-to-end annotation-processor
 * tests don't reach directly. Uses a probe processor pattern (see {@link TypeUtilTest}) to obtain a
 * real {@code ProcessingEnvironment}, build a {@code ProcessorContext}, register models, and run
 * assertions inside {@code process()}.
 *
 * <p>The end-to-end tests prove that production wiring works; this class pins the unit-level
 * contract of {@code ProcessorContext} itself (shadow-swap iteration order, register-time dedup,
 * profile-filter edge cases, the accessors).
 */
class ProcessorContextTest {

    @Test
    void shadowSwapPreservesMainOrderAndAppendsStandaloneTestComponents() {
        ShadowProbe.reset();
        compile(new ShadowProbe(), source("demo.Main", "package demo; public class Main {}"));
        assertThat(ShadowProbe.error).as("scenario error").isNull();

        // Three mains registered: A, B, C. B is shadowed by ShadowB. One standalone test
        // component (FixturesOnly) has no main counterpart. Underlying storage is HashMap so
        // iteration order isn't guaranteed — we verify the contract semantically:
        //   - B is gone, ShadowB is present (swap happened)
        //   - A and C survive
        //   - FixturesOnly is appended (it comes from the standalone-test-component loop, which
        //     runs after the main loop, so it's last)
        assertThat(ShadowProbe.resultKeys)
                .containsExactlyInAnyOrder("demo.A", "demo.ShadowB", "demo.C", "demo.FixturesOnly")
                .doesNotContain("demo.B")
                .last()
                .isEqualTo("demo.FixturesOnly");

        // Trivial accessors covered while we have a context handy.
        assertThat(ShadowProbe.activeProfilesEmpty).isTrue();
        assertThat(ShadowProbe.messagerNonNull).isTrue();
        assertThat(ShadowProbe.shadowedFlagForB).isTrue();
        assertThat(ShadowProbe.shadowedFlagForA).isFalse();
    }

    @Test
    void profileInactiveTestComponentSkippedByShadowSwap() {
        ProfileInactiveTestProbe.reset();
        compile(
                new ProfileInactiveTestProbe(),
                source("demo.Main", "package demo; public class Main {}"),
                "-Atiko.profiles=prod");
        assertThat(ProfileInactiveTestProbe.error).as("scenario error").isNull();

        // Main A is profile-active; standalone test component DevOnlyFixture carries profiles=["dev"]
        // and must be filtered out under the prod activation. Result has only A.
        assertThat(ProfileInactiveTestProbe.resultKeys).containsExactly("demo.A");
    }

    @Test
    void isProfileActiveCoversAllFourBranches() {
        IsProfileActiveProbe.reset();
        compile(new IsProfileActiveProbe(), source("demo.Main", "package demo; public class Main {}"));
        assertThat(IsProfileActiveProbe.error).as("scenario error").isNull();

        // Branches per the method docs:
        //   1. component profiles empty → always active (regardless of activeProfiles)
        //   2. activeProfiles empty, component has profiles → silent fallback, active
        //   3. profiles intersect → active
        //   4. profiles disjoint → inactive
        assertThat(IsProfileActiveProbe.emptyProfilesAlwaysActive).isTrue();
        assertThat(IsProfileActiveProbe.silentFallbackWhenNoActiveSet).isTrue();
        assertThat(IsProfileActiveProbe.intersectionMatches).isTrue();
        assertThat(IsProfileActiveProbe.disjointInactive).isFalse();
    }

    @Test
    void lookupMethodsRespectProfileFilterAndConfigurationsAndTestFallback() {
        LookupProbe.reset();
        compile(new LookupProbe(), source("demo.Main", "package demo; public class Main {}"));
        assertThat(LookupProbe.error).as("scenario error").isNull();

        // findByImplClass
        assertThat(LookupProbe.findByImplClassFactoryMatch).isTrue();
        assertThat(LookupProbe.findByImplClassFactoryProfileInactiveMisses).isTrue();
        assertThat(LookupProbe.findByImplClassNoMatchReturnsEmpty).isTrue();
        // findAllByImplClass with profile filter
        assertThat(LookupProbe.findAllByImplClassFiltersByProfile).isEqualTo(1);
        // findComponentOrFactory
        assertThat(LookupProbe.findComponentOrFactoryResolvesConfiguration).isTrue();
        assertThat(LookupProbe.findComponentOrFactoryReturnsTestFallback).isTrue();
        assertThat(LookupProbe.findComponentOrFactoryUnknownKeyReturnsEmpty).isTrue();
    }

    @Test
    void registerComponentTwiceEmitsDuplicateError() {
        DuplicateRegisterProbe.reset();
        // The probe-side error reporter routes to processingEnv.getMessager(), which surfaces
        // as a compile diagnostic. Compilation should fail (error severity).
        Compilation c = compileExpectingFailure(
                new DuplicateRegisterProbe(), source("demo.Main", "package demo; public class Main {}"));
        assertThat(DuplicateRegisterProbe.error).as("scenario error").isNull();
        CompilationSubject.assertThat(c).hadErrorContaining("Duplicate component: demo.A");
    }

    @Test
    void duplicateProducesReturnTypeRejectsAtRegistration() {
        // End-to-end: two @Produces methods returning the same type with no @Named — the
        // registerFactoryMethod error path emits a "Duplicate factory method" diagnostic.
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        source("demo.Conn", "package demo; public interface Conn { String url(); }"),
                        source(
                                "demo.Factories",
                                "package demo;",
                                "import io.tiko.Scope;",
                                "import io.tiko.annotations.Component;",
                                "import io.tiko.annotations.Produces;",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class Factories {",
                                "    @Produces(scope = Scope.SINGLETON) public Conn a() { return () -> \"a\"; }",
                                "    @Produces(scope = Scope.SINGLETON) public Conn b() { return () -> \"b\"; }",
                                "}"));
        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Duplicate factory method: demo.Conn");
    }

    @Test
    void producesReturnTypeCollidingWithComponentRejectsAtRegistration() {
        // End-to-end: a @Produces method returns a type that's already provided by a @Component.
        // The second branch of registerFactoryMethod ("already provided by a @Component") fires.
        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        source(
                                "demo.Thing",
                                "package demo;",
                                "import io.tiko.Scope;",
                                "import io.tiko.annotations.Component;",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class Thing { public String label() { return \"t\"; } }"),
                        source(
                                "demo.ThingFactory",
                                "package demo;",
                                "import io.tiko.Scope;",
                                "import io.tiko.annotations.Component;",
                                "import io.tiko.annotations.Produces;",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class ThingFactory {",
                                "    @Produces(scope = Scope.SINGLETON) public Thing make() { return new Thing(); }",
                                "}"));
        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("already provided by a @Component");
    }

    // ---- helpers ----

    private static JavaFileObject source(String fqn, String... lines) {
        return JavaFileObjects.forSourceLines(fqn, lines);
    }

    private static void compile(AbstractProcessor probe, JavaFileObject src, String... options) {
        Compiler compiler = Compiler.javac().withProcessors(probe);
        if (options.length > 0) {
            compiler = compiler.withOptions(options);
        }
        Compilation c = compiler.compile(src);
        // The probe processors throw via stashed `error` field, not via Compilation diagnostics.
        // We don't assert on Compilation status — the probes are passive readers, not the
        // production processor — so we just need them to have run.
        assertThat(c).isNotNull();
    }

    private static Compilation compileExpectingFailure(AbstractProcessor probe, JavaFileObject src) {
        return Compiler.javac().withProcessors(probe).compile(src);
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class ShadowProbe extends AbstractProcessor {

        static String error;
        static List<String> resultKeys;
        static boolean activeProfilesEmpty;
        static boolean messagerNonNull;
        static boolean shadowedFlagForB;
        static boolean shadowedFlagForA;

        static void reset() {
            error = null;
            resultKeys = null;
            activeProfilesEmpty = false;
            messagerNonNull = false;
            shadowedFlagForB = false;
            shadowedFlagForA = false;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;
            try {
                ProcessorContext ctx = new ProcessorContext(processingEnv, List.of());
                TypeElement main = processingEnv.getElementUtils().getTypeElement("demo.Main");

                ctx.registerComponent(component(main, "demo.A", false));
                ComponentModel b = component(main, "demo.B", false);
                ctx.registerComponent(b);
                ctx.registerComponent(component(main, "demo.C", false));

                ComponentModel shadowB = component(main, "demo.ShadowB", true);
                ctx.registerComponent(shadowB);
                ctx.markShadowedByTestOverride(b.getComponentKey(), shadowB);

                ctx.registerComponent(component(main, "demo.FixturesOnly", true));

                resultKeys = ctx.getActiveTestContainerComponents().stream()
                        .map(ComponentModel::getComponentKey)
                        .toList();

                activeProfilesEmpty = ctx.getActiveProfiles().isEmpty();
                Messager m = ctx.getMessager();
                messagerNonNull = (m != null);
                shadowedFlagForB = ctx.isShadowedByTestOverride("demo.B");
                shadowedFlagForA = ctx.isShadowedByTestOverride("demo.A");
            } catch (RuntimeException e) {
                error = e.toString();
            }
            return false;
        }
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class ProfileInactiveTestProbe extends AbstractProcessor {

        static String error;
        static List<String> resultKeys;

        static void reset() {
            error = null;
            resultKeys = null;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;
            try {
                ProcessorContext ctx = new ProcessorContext(processingEnv, List.of("prod"));
                TypeElement main = processingEnv.getElementUtils().getTypeElement("demo.Main");

                ctx.registerComponent(component(main, "demo.A", false));
                ctx.registerComponent(ComponentModel.builder()
                        .typeElement(main)
                        .qualifiedName("demo.DevOnlyFixture")
                        .className("DevOnlyFixture")
                        .packageName("demo")
                        .scope(Scope.SINGLETON)
                        .constructor(constructorOf(main))
                        .profiles(List.of("dev"))
                        .testComponent(true)
                        .build());

                resultKeys = ctx.getActiveTestContainerComponents().stream()
                        .map(ComponentModel::getComponentKey)
                        .toList();
            } catch (RuntimeException e) {
                error = e.toString();
            }
            return false;
        }
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class IsProfileActiveProbe extends AbstractProcessor {

        static String error;
        static boolean emptyProfilesAlwaysActive;
        static boolean silentFallbackWhenNoActiveSet;
        static boolean intersectionMatches;
        static boolean disjointInactive;

        static void reset() {
            error = null;
            emptyProfilesAlwaysActive = false;
            silentFallbackWhenNoActiveSet = false;
            intersectionMatches = false;
            disjointInactive = false;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;
            try {
                // Branches 1 + 2 share one context with no active profiles.
                ProcessorContext noActive = new ProcessorContext(processingEnv, List.of());
                emptyProfilesAlwaysActive = noActive.isProfileActive(List.of());
                silentFallbackWhenNoActiveSet = noActive.isProfileActive(List.of("dev"));

                // Branches 3 + 4: prod active, two component-profile sets to compare against.
                ProcessorContext prodActive = new ProcessorContext(processingEnv, List.of("prod"));
                intersectionMatches = prodActive.isProfileActive(List.of("prod", "staging"));
                disjointInactive = prodActive.isProfileActive(List.of("dev"));
            } catch (RuntimeException e) {
                error = e.toString();
            }
            return false;
        }
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class LookupProbe extends AbstractProcessor {

        static String error;
        static boolean findByImplClassFactoryMatch;
        static boolean findByImplClassFactoryProfileInactiveMisses;
        static boolean findByImplClassNoMatchReturnsEmpty;
        static int findAllByImplClassFiltersByProfile;
        static boolean findComponentOrFactoryResolvesConfiguration;
        static boolean findComponentOrFactoryReturnsTestFallback;
        static boolean findComponentOrFactoryUnknownKeyReturnsEmpty;

        static void reset() {
            error = null;
            findByImplClassFactoryMatch = false;
            findByImplClassFactoryProfileInactiveMisses = false;
            findByImplClassNoMatchReturnsEmpty = false;
            findAllByImplClassFiltersByProfile = -1;
            findComponentOrFactoryResolvesConfiguration = false;
            findComponentOrFactoryReturnsTestFallback = false;
            findComponentOrFactoryUnknownKeyReturnsEmpty = false;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;
            try {
                TypeElement main = processingEnv.getElementUtils().getTypeElement("demo.Main");

                // === findByImplClass factory branch ===
                ProcessorContext ctxFactoryActive = new ProcessorContext(processingEnv, List.of("prod"));
                ctxFactoryActive.registerFactoryMethod(factory(main, "demo.Conn", "buildConn", List.of("prod")));
                findByImplClassFactoryMatch =
                        ctxFactoryActive.findByImplClass("demo.Conn").isPresent();
                ctxFactoryActive.registerFactoryMethod(factory(main, "demo.OtherConn", "devConn", List.of("dev")));
                findByImplClassFactoryProfileInactiveMisses =
                        ctxFactoryActive.findByImplClass("demo.OtherConn").isEmpty();
                findByImplClassNoMatchReturnsEmpty =
                        ctxFactoryActive.findByImplClass("demo.NeverRegistered").isEmpty();

                // === findAllByImplClass with profile filter ===
                // Two components for same impl-class FQN — different qualifiers, disjoint profiles.
                // (Same impl class can't really exist twice in one classpath, but findAllByImplClass
                // iterates the map without dedup-by-class, so registering two distinct keys whose
                // qualified-name matches the queried FQN exercises the filter loop. Use named
                // components to avoid the registration-collision check.)
                ProcessorContext ctxAll = new ProcessorContext(processingEnv, List.of("prod"));
                ctxAll.registerComponent(namedComponent(main, "demo.Same", "prodVariant", List.of("prod")));
                ctxAll.registerComponent(namedComponent(main, "demo.Same", "devVariant", List.of("dev")));
                findAllByImplClassFiltersByProfile =
                        ctxAll.findAllByImplClass("demo.Same").size();

                // === findComponentOrFactory configuration branch ===
                ProcessorContext ctxCfg = new ProcessorContext(processingEnv, List.of());
                ctxCfg.registerConfiguration(new io.tiko.processor.config.ConfigurationModel(
                        main, "demo", "DbConfig", "demo.DbConfig", "db", List.of()));
                findComponentOrFactoryResolvesConfiguration =
                        ctxCfg.findComponentOrFactory("demo.DbConfig").isPresent();

                // === findComponentOrFactory testFallback branch ===
                // Interface impl provided by a @TestComponent only — main lookup should fall back
                // to the test bean (the only available impl), exercising the `testFallback` path.
                ProcessorContext ctxFallback = new ProcessorContext(processingEnv, List.of());
                TypeElement objectTe = processingEnv.getElementUtils().getTypeElement("java.lang.Runnable");
                ComponentModel testImpl = ComponentModel.builder()
                        .typeElement(main)
                        .qualifiedName("demo.TestRunnableImpl")
                        .className("TestRunnableImpl")
                        .packageName("demo")
                        .scope(Scope.SINGLETON)
                        .constructor(constructorOf(main))
                        .implementedInterface(objectTe.asType())
                        .testComponent(true)
                        .build();
                ctxFallback.registerComponent(testImpl);
                findComponentOrFactoryReturnsTestFallback =
                        ctxFallback.findComponentOrFactory("java.lang.Runnable").isPresent();
                findComponentOrFactoryUnknownKeyReturnsEmpty =
                        ctxFallback.findComponentOrFactory("demo.NeverThere").isEmpty();
            } catch (RuntimeException e) {
                error = e.toString();
            }
            return false;
        }
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    public static class DuplicateRegisterProbe extends AbstractProcessor {

        static String error;

        static void reset() {
            error = null;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) return false;
            try {
                ProcessorContext ctx = new ProcessorContext(processingEnv, List.of());
                TypeElement main = processingEnv.getElementUtils().getTypeElement("demo.Main");
                ctx.registerComponent(component(main, "demo.A", false));
                // Second registration with the same key triggers the duplicate-error path.
                ctx.registerComponent(component(main, "demo.A", false));
            } catch (RuntimeException e) {
                error = e.toString();
            }
            return false;
        }
    }

    private static ComponentModel component(TypeElement teForErrorReports, String fqn, boolean isTest) {
        int dot = fqn.lastIndexOf('.');
        return ComponentModel.builder()
                .typeElement(teForErrorReports)
                .qualifiedName(fqn)
                .className(fqn.substring(dot + 1))
                .packageName(fqn.substring(0, dot))
                .scope(Scope.SINGLETON)
                .constructor(constructorOf(teForErrorReports))
                .testComponent(isTest)
                .build();
    }

    private static ComponentModel namedComponent(
            TypeElement teForErrorReports, String fqn, String qualifier, List<String> profiles) {
        int dot = fqn.lastIndexOf('.');
        return ComponentModel.builder()
                .typeElement(teForErrorReports)
                .qualifiedName(fqn)
                .className(fqn.substring(dot + 1))
                .packageName(fqn.substring(0, dot))
                .scope(Scope.SINGLETON)
                .constructor(constructorOf(teForErrorReports))
                .name(qualifier)
                .profiles(profiles)
                .build();
    }

    private static FactoryMethodModel factory(
            TypeElement declaring, String returnTypeFqn, String methodName, List<String> profiles) {
        // Pick an arbitrary ExecutableElement off the declaring class to satisfy the builder's
        // not-null contract. We never invoke the method element through here — it's a marker.
        var methodEl = (javax.lang.model.element.ExecutableElement) declaring.getEnclosedElements().stream()
                .filter(e -> e.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR)
                .findFirst()
                .orElseThrow();
        return FactoryMethodModel.builder()
                .methodElement(methodEl)
                .declaringClass(declaring)
                .methodName(methodName)
                .returnType(declaring.asType())
                .returnTypeName(returnTypeFqn)
                .scope(Scope.SINGLETON)
                .profiles(profiles)
                .build();
    }

    private static javax.lang.model.element.ExecutableElement constructorOf(TypeElement te) {
        for (var enclosed : te.getEnclosedElements()) {
            if (enclosed.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR) {
                return (javax.lang.model.element.ExecutableElement) enclosed;
            }
        }
        throw new IllegalStateException("no constructor on " + te);
    }
}
