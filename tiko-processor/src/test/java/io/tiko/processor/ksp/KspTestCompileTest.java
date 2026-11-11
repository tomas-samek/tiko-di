package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.processor.collect.MainModuleOnClasspath;
import io.tiko.processor.core.javac.JavacProcessingEnv;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import io.tiko.processor.core.ksp.KspProcessingEnv;
import io.tiko.processor.core.ksp.KspProgram;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A KSP test compile against a separately compiled main module: the main module's generated
 * classes on the classpath make the round a standalone test compile, as {@code META-INF/tiko}
 * resources do on javac.
 */
class KspTestCompileTest {

    private static final String TEST_COMPONENT_ANNOTATION = """
            package io.tiko.test;

            import io.tiko.Scope;
            import java.lang.annotation.*;

            @Retention(RetentionPolicy.SOURCE)
            @Target(ElementType.TYPE)
            public @interface TestComponent {
                Class<?> value() default Void.class;
                Scope scope() default Scope.SINGLETON;
                String name() default "";
            }
            """;

    private static final String CLOCK_MAIN = """
            package demo

            import io.tiko.Scope
            import io.tiko.annotations.Component

            @Component(scope = Scope.SINGLETON)
            open class Clock {
                open fun now() = "real"
            }
            """;

    private static final String FAKE_CLOCK_TEST = """
            package demo

            import io.tiko.test.TestComponent

            @TestComponent
            class FakeClock : Clock() {
                override fun now() = "fake"
            }
            """;

    private static final String TEST_CONTAINER_DESCRIPTOR = "META-INF/tiko/test-container.properties";
    private static final String TEST_SHADOWS = "META-INF/tiko/test-shadows.properties";
    private static final String CONTAINER_DESCRIPTOR = "META-INF/tiko/container.properties";
    private static final String COMPONENTS_MANIFEST = "META-INF/tiko/components.txt";

    @Test
    void generatedClassesNameTheSameComponentsAsTheManifest() {
        var main = compileMain(
                KspCompiler.kotlin("Graph.kt", """
                        package demo

                        import io.tiko.Scope
                        import io.tiko.annotations.Component
                        import io.tiko.annotations.Produces

                        interface Greeter { fun greet(): String }

                        class Clock

                        @Component(scope = Scope.SINGLETON)
                        class Repository

                        @Component
                        class Service(val repository: Repository)

                        @Component(scope = Scope.EVENT)
                        class RequestGreeter : Greeter { override fun greet() = "hi" }

                        @Component(scope = Scope.SINGLETON)
                        class UsesGreeter(val greeter: Greeter)

                        @Component(scope = Scope.SINGLETON)
                        class Clocks {
                            @Produces fun clock(): Clock = Clock()
                        }

                        class Outer {
                            @Component
                            class Nested
                        }
                        """),
                KspCompiler.java(
                        "JavaService.java",
                        "package demo;",
                        "",
                        "@io.tiko.annotations.Component",
                        "public class JavaService {",
                        "    public JavaService(Repository repository) {}",
                        "}"));

        var seen = mainModuleSeenBy(librariesOf(main));

        var manifest = main.generatedResource(COMPONENTS_MANIFEST)
                .orElseThrow()
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        assertThat(seen.componentFqns()).containsExactlyInAnyOrderElementsOf(manifest);
        assertThat(seen.containerFqn())
                .hasValue(implOf(main.generatedResource(CONTAINER_DESCRIPTOR).orElseThrow()));
    }

    @Test
    void testCompileEmitsAStandaloneTestContainer() {
        var main = compileMain(KspCompiler.kotlin("Clock.kt", CLOCK_MAIN));

        var test = compileTest(librariesOf(main), KspCompiler.kotlin("FakeClock.kt", FAKE_CLOCK_TEST));

        assertThat(test.generatedJavaNames())
                .filteredOn(name -> name.startsWith("io.tiko.generated.TestContainerImpl_"))
                .hasSize(1);
        assertThat(test.generatedResource(TEST_CONTAINER_DESCRIPTOR)).isPresent();
        assertThat(test.generatedResource(TEST_SHADOWS))
                .hasValueSatisfying(
                        shadows -> assertThat(shadows.lines()).anyMatch(line -> line.startsWith("demo.Clock=")));
    }

    @Test
    void implicitWalkShadowsTheMainComponent() throws Exception {
        var main = compileMain(KspCompiler.kotlin("Clock.kt", CLOCK_MAIN));
        var test = compileTest(librariesOf(main), KspCompiler.kotlin("FakeClock.kt", FAKE_CLOCK_TEST));

        try (var program = KspProgram.bootTestWiring(test)) {
            var clock = program.get("demo.Clock");
            assertThat(clock.getClass().getMethod("now").invoke(clock)).isEqualTo("fake");
        }
    }

    /**
     * An explicit {@code value()} naming an interface the main component implements. javac's
     * two-phase compile is the reference: KSP must boot the same component for the interface.
     */
    @Test
    void explicitValueShadowsAnInterfaceKey(@TempDir Path dir) throws Exception {
        var javacMain = javacCompile(
                List.of(),
                JavaFileObjects.forSourceLines("demo.Clock", "package demo;", "public interface Clock {}"),
                JavaFileObjects.forSourceLines(
                        "demo.SystemClock",
                        "package demo;",
                        "@io.tiko.annotations.Component",
                        "public class SystemClock implements Clock {}"),
                JavaFileObjects.forSourceLines(
                        "demo.Scheduler",
                        "package demo;",
                        "@io.tiko.annotations.Component",
                        "public class Scheduler {",
                        "    private final Clock clock;",
                        "    public Scheduler(Clock clock) { this.clock = clock; }",
                        "    public Clock getClock() { return clock; }",
                        "}"));
        var mainClasses = writeClassOutput(javacMain, dir.resolve("main"));
        var javacTest = javacCompile(
                List.of(mainClasses.toFile()),
                JavaFileObjects.forSourceString("io.tiko.test.TestComponent", TEST_COMPONENT_ANNOTATION),
                JavaFileObjects.forSourceLines(
                        "demo.FixedClock",
                        "package demo;",
                        "@io.tiko.test.TestComponent(Clock.class)",
                        "public class FixedClock implements Clock {}"));
        var testClasses = writeClassOutput(javacTest, dir.resolve("test"));
        assertThat(Files.readString(testClasses.resolve(TEST_SHADOWS)).lines())
                .anyMatch(line -> line.startsWith("demo.Clock=") && line.endsWith("|demo.FixedClock"));
        String javacBooted;
        String javacInjected;
        try (var program = KspProgram.bootTestWiring(programOver(testClasses, mainClasses))) {
            javacBooted = program.get("demo.Clock").getClass().getName();
            javacInjected = clockOfScheduler(program);
        }

        var main = compileMain(KspCompiler.kotlin("Clock.kt", """
                package demo

                import io.tiko.annotations.Component

                interface Clock

                @Component
                class SystemClock : Clock

                @Component
                class Scheduler(val clock: Clock)
                """));
        var test = compileTest(librariesOf(main), KspCompiler.kotlin("FixedClock.kt", """
                        package demo

                        import io.tiko.test.TestComponent

                        @TestComponent(Clock::class)
                        class FixedClock : Clock
                        """));

        assertThat(test.generatedResource(TEST_SHADOWS))
                .hasValueSatisfying(shadows -> assertThat(shadows.lines())
                        .anyMatch(line -> line.startsWith("demo.Clock=") && line.endsWith("|demo.FixedClock")));
        try (var program = KspProgram.bootTestWiring(test)) {
            assertThat(program.get("demo.Clock").getClass().getName())
                    .isEqualTo(javacBooted)
                    .isEqualTo("demo.FixedClock");
            assertThat(clockOfScheduler(program)).isEqualTo(javacInjected).isEqualTo("demo.FixedClock");
        }
    }

    /**
     * An implicit {@code @TestComponent} extending a main component shadows the superclass's
     * interfaces too: a main component injecting one gets the test component on both frontends.
     * The lifecycle marker {@code AutoCloseable} is no dispatch key and stays unshadowed.
     */
    @Test
    void implicitTestComponentShadowsTheInterfacesOfItsSuperclass(@TempDir Path dir) throws Exception {
        var javacMain = javacCompile(
                List.of(),
                JavaFileObjects.forSourceLines("demo.Clock", "package demo;", "public interface Clock {}"),
                JavaFileObjects.forSourceLines(
                        "demo.Scheduler",
                        "package demo;",
                        "@io.tiko.annotations.Component",
                        "public class Scheduler implements Clock, AutoCloseable {",
                        "    public void close() {}",
                        "}"),
                JavaFileObjects.forSourceLines(
                        "demo.Consumer",
                        "package demo;",
                        "@io.tiko.annotations.Component",
                        "public class Consumer {",
                        "    private final Clock clock;",
                        "    public Consumer(Clock clock) { this.clock = clock; }",
                        "    public Clock getClock() { return clock; }",
                        "}"));
        var mainClasses = writeClassOutput(javacMain, dir.resolve("main"));
        var javacTest = javacCompile(
                List.of(mainClasses.toFile()),
                JavaFileObjects.forSourceString("io.tiko.test.TestComponent", TEST_COMPONENT_ANNOTATION),
                JavaFileObjects.forSourceLines(
                        "demo.FixedScheduler",
                        "package demo;",
                        "@io.tiko.test.TestComponent",
                        "public class FixedScheduler extends Scheduler {}"));
        var testClasses = writeClassOutput(javacTest, dir.resolve("test"));
        assertThat(Files.readString(testClasses.resolve(TEST_SHADOWS)).lines())
                .anyMatch(line -> line.startsWith("demo.Clock=") && line.endsWith("|demo.FixedScheduler"))
                .noneMatch(line -> line.startsWith("java.lang.AutoCloseable="));
        String javacInjected;
        try (var program = KspProgram.bootTestWiring(programOver(testClasses, mainClasses))) {
            javacInjected = clockOf(program, "demo.Consumer");
        }

        var main = compileMain(KspCompiler.kotlin("Clock.kt", """
                package demo

                import io.tiko.annotations.Component

                interface Clock

                @Component
                open class Scheduler : Clock, AutoCloseable {
                    override fun close() {}
                }

                @Component
                class Consumer(val clock: Clock)
                """));
        var test = compileTest(librariesOf(main), KspCompiler.kotlin("FixedScheduler.kt", """
                        package demo

                        import io.tiko.test.TestComponent

                        @TestComponent
                        class FixedScheduler : Scheduler()
                        """));

        assertThat(test.generatedResource(TEST_SHADOWS))
                .hasValueSatisfying(shadows -> assertThat(shadows.lines())
                        .anyMatch(line -> line.startsWith("demo.Clock=") && line.endsWith("|demo.FixedScheduler"))
                        .noneMatch(line -> line.startsWith("java.lang.AutoCloseable=")));
        try (var program = KspProgram.bootTestWiring(test)) {
            assertThat(clockOf(program, "demo.Consumer"))
                    .isEqualTo(javacInjected)
                    .isEqualTo("demo.FixedScheduler");
        }
    }

    /** The class of the {@code Clock} the main {@code Scheduler} component was wired with. */
    private static String clockOfScheduler(KspProgram program) throws Exception {
        return clockOf(program, "demo.Scheduler");
    }

    /** The class of the {@code Clock} the main {@code component} was wired with. */
    private static String clockOf(KspProgram program, String component) throws Exception {
        var scheduler = program.get(component);
        return scheduler
                .getClass()
                .getMethod("getClock")
                .invoke(scheduler)
                .getClass()
                .getName();
    }

    @Test
    void testCompileSeesComponentsOfEveryCompiledModule() {
        var alpha = compileMain(
                KspCompiler.kotlin("Alpha.kt", "package a", "", "@io.tiko.annotations.Component", "open class Alpha"));
        var beta = compileMain(
                KspCompiler.kotlin("Beta.kt", "package b", "", "@io.tiko.annotations.Component", "open class Beta"));
        var libraries = new ArrayList<File>(librariesOf(alpha));
        libraries.addAll(librariesOf(beta));

        var test = compileTest(libraries, KspCompiler.kotlin("Fakes.kt", """
                        package fakes

                        import io.tiko.test.TestComponent

                        @TestComponent
                        class FakeAlpha : a.Alpha()

                        @TestComponent
                        class FakeBeta : b.Beta()
                        """));

        assertThat(test.generatedJavaNames()).anyMatch(name -> name.startsWith("io.tiko.generated.TestContainerImpl_"));
        assertThat(test.generatedResource(TEST_SHADOWS))
                .hasValueSatisfying(shadows -> assertThat(shadows.lines())
                        .anyMatch(line -> line.startsWith("a.Alpha="))
                        .anyMatch(line -> line.startsWith("b.Beta=")));
    }

    /** The javac counterpart of {@link #testCompileSeesComponentsOfEveryCompiledModule}: same shadows. */
    @Test
    void javacTestCompileSeesComponentsOfEveryCompiledModule(@TempDir Path dir) throws Exception {
        var alpha = javacCompile(
                List.of(),
                JavaFileObjects.forSourceLines(
                        "a.Alpha", "package a;", "@io.tiko.annotations.Component", "public class Alpha {}"));
        var beta = javacCompile(
                List.of(),
                JavaFileObjects.forSourceLines(
                        "b.Beta", "package b;", "@io.tiko.annotations.Component", "public class Beta {}"));
        var alphaClasses = writeClassOutput(alpha, dir.resolve("alpha"));
        var betaClasses = writeClassOutput(beta, dir.resolve("beta"));

        var test = javacCompile(
                List.of(alphaClasses.toFile(), betaClasses.toFile()),
                JavaFileObjects.forSourceString("io.tiko.test.TestComponent", TEST_COMPONENT_ANNOTATION),
                JavaFileObjects.forSourceLines(
                        "fakes.FakeAlpha",
                        "package fakes;",
                        "@io.tiko.test.TestComponent",
                        "public class FakeAlpha extends a.Alpha {}"),
                JavaFileObjects.forSourceLines(
                        "fakes.FakeBeta",
                        "package fakes;",
                        "@io.tiko.test.TestComponent",
                        "public class FakeBeta extends b.Beta {}"));

        var testClasses = writeClassOutput(test, dir.resolve("test"));
        assertThat(Files.readString(testClasses.resolve(TEST_SHADOWS)).lines())
                .anyMatch(line -> line.startsWith("a.Alpha="))
                .anyMatch(line -> line.startsWith("b.Beta="));
    }

    /** Two modules on the classpath in either order: both frontends pick the lowest-named module's container. */
    @Test
    void bothFrontendsPickTheSameContainerInEitherClasspathOrder(@TempDir Path dir) throws Exception {
        var javacAlpha = writeClassOutput(
                javacCompile(
                        List.of(),
                        JavaFileObjects.forSourceLines(
                                "a.Alpha", "package a;", "@io.tiko.annotations.Component", "public class Alpha {}")),
                dir.resolve("alpha"));
        var javacBeta = writeClassOutput(
                javacCompile(
                        List.of(),
                        JavaFileObjects.forSourceLines(
                                "b.Beta", "package b;", "@io.tiko.annotations.Component", "public class Beta {}")),
                dir.resolve("beta"));
        var kspAlpha = compileMain(
                KspCompiler.kotlin("Alpha.kt", "package a", "", "@io.tiko.annotations.Component", "open class Alpha"));
        var kspBeta = compileMain(
                KspCompiler.kotlin("Beta.kt", "package b", "", "@io.tiko.annotations.Component", "open class Beta"));

        var javacContainers = List.of(
                implOf(Files.readString(javacAlpha.resolve(CONTAINER_DESCRIPTOR))),
                implOf(Files.readString(javacBeta.resolve(CONTAINER_DESCRIPTOR))));
        var kspContainers = List.of(
                implOf(kspAlpha.generatedResource(CONTAINER_DESCRIPTOR).orElseThrow()),
                implOf(kspBeta.generatedResource(CONTAINER_DESCRIPTOR).orElseThrow()));
        var javacLowest = javacContainers.stream().sorted().findFirst().orElseThrow();
        var kspLowest = kspContainers.stream().sorted().findFirst().orElseThrow();

        for (var javacOrder : List.of(List.of(javacAlpha, javacBeta), List.of(javacBeta, javacAlpha))) {
            assertThat(javacMainModuleSeenBy(
                                    javacOrder.stream().map(Path::toFile).toList())
                            .containerFqn())
                    .hasValue(javacLowest);
        }
        var alphaLibraries = librariesOf(kspAlpha);
        var betaLibraries = librariesOf(kspBeta);
        var forward = new ArrayList<File>(alphaLibraries);
        forward.addAll(betaLibraries);
        var backward = new ArrayList<File>(betaLibraries);
        backward.addAll(alphaLibraries);
        assertThat(mainModuleSeenBy(forward).containerFqn()).hasValue(kspLowest);
        assertThat(mainModuleSeenBy(backward).containerFqn()).hasValue(kspLowest);
    }

    /** What {@link MainModuleOnClasspath} reads in a javac round compiled against {@code libraries}. */
    private static MainModuleOnClasspath javacMainModuleSeenBy(List<File> libraries) {
        var seen = new AtomicReference<MainModuleOnClasspath>();
        var probe = new AbstractProcessor() {
            @Override
            public Set<String> getSupportedAnnotationTypes() {
                return Set.of("*");
            }

            @Override
            public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
            }

            @Override
            public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                if (seen.get() == null) {
                    seen.set(MainModuleOnClasspath.read(JavacProcessingEnv.of(processingEnv, round)));
                }
                return false;
            }
        };
        var compilation = Compiler.javac()
                .withProcessors(probe)
                .withClasspath(libraries)
                .compile(JavaFileObjects.forSourceLines("probe.Probe", "package probe;", "public class Probe {}"));
        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(seen.get()).as("the probe ran").isNotNull();
        return seen.get();
    }

    @Test
    void testOnlyModuleKeepsTheMainContainerName() {
        var javac = javacCompile(
                List.of(),
                JavaFileObjects.forSourceString("io.tiko.test.TestComponent", TEST_COMPONENT_ANNOTATION),
                JavaFileObjects.forSourceLines(
                        "fakes.FakeOnly", "package fakes;", "@io.tiko.test.TestComponent", "public class FakeOnly {}"));

        var test = compileTest(List.of(), KspCompiler.kotlin("Fakes.kt", """
                        package fakes

                        import io.tiko.test.TestComponent

                        @TestComponent
                        class FakeOnly
                        """));

        assertThat(test.generatedJavaNames())
                .anyMatch(name -> name.startsWith("io.tiko.generated.TikoContainerImpl_"))
                .containsExactlyInAnyOrderElementsOf(generatedJavaNames(javac));
        assertThat(test.generatedResource(TEST_CONTAINER_DESCRIPTOR).isPresent())
                .as("test-container.properties as on javac")
                .isEqualTo(javacGenerated(javac, TEST_CONTAINER_DESCRIPTOR));
    }

    @Test
    void testComponentInMainSourcesIsRejected() {
        var main = compileMain(KspCompiler.kotlin("Clock.kt", CLOCK_MAIN));

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider()),
                Map.of(),
                withTestComponentAnnotation(librariesOf(main)),
                KspCompiler.kotlin("src/main/kotlin/demo/FakeClock.kt", FAKE_CLOCK_TEST));

        assertThat(result.exitCode()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages()).contains("@TestComponent FakeClock is in production sources");
    }

    private static KspResult compileMain(SourceFile... sources) {
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), sources);
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        return result;
    }

    /** Compiles test sources against {@code libraries}, with the test-support annotation on the classpath. */
    private static KspResult compileTest(List<File> libraries, SourceFile... sources) {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider()), Map.of(), withTestComponentAnnotation(libraries), sources);
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        return result;
    }

    private static List<File> withTestComponentAnnotation(List<File> libraries) {
        var classpath = new ArrayList<>(libraries);
        classpath.add(KspCompiler.library(KspCompiler.java("TestComponent.java", TEST_COMPONENT_ANNOTATION)));
        return classpath;
    }

    /** A compiled module's output roots (classes and generated resources), as a later compile's libraries. */
    private static List<File> librariesOf(KspResult result) {
        return result.outputRoots().stream().map(Path::toFile).toList();
    }

    /** What {@link MainModuleOnClasspath} reads in a KSP round compiled against {@code libraries}. */
    private static MainModuleOnClasspath mainModuleSeenBy(List<File> libraries) {
        var seen = new AtomicReference<MainModuleOnClasspath>();
        SymbolProcessorProvider probe = environment -> resolver -> {
            if (seen.get() == null) {
                seen.set(MainModuleOnClasspath.read(KspProcessingEnv.of(
                        resolver, environment.getCodeGenerator(), environment.getLogger(), environment.getOptions())));
            }
            return List.<KSAnnotated>of();
        };
        KspCompiler.compile(
                List.of(probe),
                Map.of(),
                libraries,
                KspCompiler.kotlin("Probe.kt", "package probe", "", "class Probe"));
        assertThat(seen.get()).as("the probe ran").isNotNull();
        return seen.get();
    }

    private static String implOf(String descriptor) {
        var props = new Properties();
        try {
            props.load(new StringReader(descriptor));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props.getProperty("impl");
    }

    /** A javac compile with the DI processor over {@code libraries} plus the test's classpath. */
    private static Compilation javacCompile(List<File> libraries, JavaFileObject... sources) {
        var classpath = new ArrayList<>(libraries);
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            classpath.add(new File(entry));
        }
        var compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withClasspath(classpath)
                .compile(sources);
        CompilationSubject.assertThat(compilation).succeeded();
        return compilation;
    }

    /** The fully qualified names of the Java sources the compile generated. */
    private static List<String> generatedJavaNames(Compilation compilation) {
        var prefix = "/" + StandardLocation.SOURCE_OUTPUT.getName() + "/";
        return compilation.generatedSourceFiles().stream()
                .map(file -> file.toUri().getPath())
                .filter(path -> path.startsWith(prefix))
                .map(path -> path.substring(prefix.length(), path.length() - ".java".length())
                        .replace('/', '.'))
                .toList();
    }

    /** Whether the compile generated the class-output resource. */
    private static boolean javacGenerated(Compilation compilation, String resource) {
        var path = "/" + StandardLocation.CLASS_OUTPUT.getName() + "/" + resource;
        return compilation.generatedFiles().stream()
                .anyMatch(file -> file.toUri().getPath().equals(path));
    }

    /** Writes the compile's class output (classes and resources) under {@code root}. */
    private static Path writeClassOutput(Compilation compilation, Path root) throws IOException {
        var prefix = "/" + StandardLocation.CLASS_OUTPUT.getName() + "/";
        for (var file : compilation.generatedFiles()) {
            var path = file.toUri().getPath();
            if (path.startsWith(prefix)) {
                var target = root.resolve(path.substring(prefix.length()));
                Files.createDirectories(target.getParent());
                try (var in = file.openInputStream()) {
                    Files.write(target, in.readAllBytes());
                }
            }
        }
        return root;
    }

    /** A result whose output roots are javac's test classes, then its main classes. */
    private static KspResult programOver(Path testClasses, Path mainClasses) {
        return new KspResult(KotlinCompilation.ExitCode.OK, "", Map.of(), Map.of(), List.of(testClasses, mainClasses));
    }
}
