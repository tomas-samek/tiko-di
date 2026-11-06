package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.Dependencies;
import com.google.devtools.ksp.processing.SymbolProcessor;
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment;
import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import kotlin.sequences.SequencesKt;

/**
 * A test {@link SymbolProcessorProvider} that generates one plain Kotlin file (no annotations) in
 * the first round, which makes KSP run a second round, and records the new files of every round.
 * A processor that writes its output in a round makes KSP run one more, so the record shows in
 * which round another processor generated. Shared through the core test-jar.
 */
public final class RoundWatcher implements SymbolProcessorProvider {

    private final List<List<String>> newFilesPerRound = new ArrayList<>();

    /** The names of each round's new files, first round first. */
    public List<List<String>> newFilesPerRound() {
        return newFilesPerRound;
    }

    @Override
    public SymbolProcessor create(SymbolProcessorEnvironment environment) {
        return resolver -> {
            var names = SequencesKt.toList(resolver.getNewFiles()).stream()
                    .map(KSFile::getFileName)
                    .toList();
            if (newFilesPerRound.isEmpty()) {
                generatePlainFile(environment);
            }
            newFilesPerRound.add(names);
            return List.of();
        };
    }

    private static void generatePlainFile(SymbolProcessorEnvironment environment) {
        try (var out = environment.getCodeGenerator().createNewFile(new Dependencies(false), "watch", "Plain", "kt")) {
            out.write("package watch\n\nclass Plain\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
