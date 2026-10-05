package io.tiko.mcp;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test helper: creates a symbolic link, or skips the test where the OS doesn't permit one. */
public final class Symlinks {

    private Symlinks() {}

    /**
     * Creates {@code link} → {@code target}. Windows without Developer Mode refuses symlink
     * creation ("A required privilege is not held"); the test is then skipped, not failed. CI
     * (Linux) always runs it.
     */
    public static void linkOrSkip(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links not supported here: " + e.getMessage());
        }
    }
}
