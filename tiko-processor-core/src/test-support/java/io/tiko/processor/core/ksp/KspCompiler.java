package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.KspKt;
import com.tschuchort.compiletesting.SourceFile;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kotlin.Unit;

/**
 * Runs KSP2 over Kotlin (and Java) sources with kctfork, then compiles the sources together with
 * everything the processors generated, in one run. A test helper shared with the processor
 * modules' tests through the core test-jar.
 *
 * <p>The compile classpath is the test's own classpath, so the Tiko API and runtime are visible to
 * the sources and to the generated Java.
 */
public final class KspCompiler {

    private KspCompiler() {}

    /** A Kotlin source file; {@code lines} are joined with newlines. */
    public static SourceFile kotlin(String fileName, String... lines) {
        return SourceFile.Companion.kotlin(fileName, String.join("\n", lines), false);
    }

    /** A Java source file; {@code lines} are joined with newlines. */
    public static SourceFile java(String fileName, String... lines) {
        return SourceFile.Companion.java(fileName, String.join("\n", lines), false);
    }

    public static KspResult compile(SymbolProcessorProvider provider, SourceFile... sources) {
        return compile(List.of(provider), Map.of(), sources);
    }

    /** Runs every provider in the same KSP run, in list order, with the given processor options. */
    public static KspResult compile(
            List<SymbolProcessorProvider> providers, Map<String, String> options, SourceFile... sources) {
        return compile(providers, options, List.of(), sources);
    }

    /** Compiles the sources, without processors, to a class directory a later run can take as a library. */
    public static File library(SourceFile... sources) {
        var compilation = new KotlinCompilation();
        compilation.setSources(List.of(sources));
        compilation.setInheritClassPath(true);
        compilation.setJvmTarget("21");
        compilation.setMessageOutputStream(OutputStream.nullOutputStream());
        var result = compilation.compile();
        if (result.getExitCode() != KotlinCompilation.ExitCode.OK) {
            throw new IllegalStateException(result.getMessages());
        }
        return result.getOutputDirectory();
    }

    /** As above, with separately compiled library class directories on the compile classpath. */
    public static KspResult compile(
            List<SymbolProcessorProvider> providers,
            Map<String, String> options,
            List<File> libraries,
            SourceFile... sources) {
        var compilation = new KotlinCompilation();
        compilation.setSources(List.of(sources));
        compilation.setClasspaths(libraries);
        compilation.setInheritClassPath(true);
        compilation.setJvmTarget("21");
        compilation.setMessageOutputStream(OutputStream.nullOutputStream());
        KspKt.configureKsp(compilation, tool -> {
            tool.getSymbolProcessorProviders().addAll(providers);
            tool.getProcessorOptions().putAll(options);
            return Unit.INSTANCE;
        });
        var result = compilation.compile();
        var generated = KspKt.getKspSourcesDir(compilation).toPath();
        return new KspResult(
                result.getExitCode(),
                result.getMessages(),
                readTree(generated.resolve("java"), ".java"),
                readTree(generated.resolve("resources"), ""),
                Stream.concat(
                                Stream.of(result.getOutputDirectory().toPath(), generated.resolve("resources")),
                                libraries.stream().map(File::toPath))
                        .toList());
    }

    /** Every file under {@code root} with the suffix, keyed by its path relative to the root. */
    private static Map<String, String> readTree(Path root, String suffix) {
        if (!Files.isDirectory(root)) {
            return Map.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(suffix))
                    .collect(Collectors.toMap(
                            file -> root.relativize(file).toString().replace(File.separatorChar, '/'),
                            KspCompiler::read,
                            (a, b) -> a,
                            TreeMap::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The outcome of a {@link KspCompiler} run: the exit code of the whole run (processing and
     * compilation), every compiler message, the generated sources and resources, and the output
     * roots (the compiled classes, then the generated resources) a class loader needs to run the
     * compiled program.
     */
    public record KspResult(
            KotlinCompilation.ExitCode exitCode,
            String messages,
            Map<String, String> javaSources,
            Map<String, String> resources,
            List<Path> outputRoots) {

        /** The generated Java source of the type, by fully qualified name. */
        public Optional<String> generatedJava(String fqn) {
            return Optional.ofNullable(javaSources.get(fqn.replace('.', '/') + ".java"));
        }

        /** The fully qualified names of every generated Java type. */
        public Set<String> generatedJavaNames() {
            return javaSources.keySet().stream()
                    .map(path ->
                            path.substring(0, path.length() - ".java".length()).replace('/', '.'))
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }

        /** A generated resource, by its path relative to the class output root. */
        public Optional<String> generatedResource(String path) {
            return Optional.ofNullable(resources.get(path));
        }
    }
}
