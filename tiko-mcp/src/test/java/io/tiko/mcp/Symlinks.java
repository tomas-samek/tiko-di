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

    /**
     * Creates a directory link {@code link} → {@code target}: a symbolic link where the OS allows
     * one, otherwise (Windows without the privilege) a directory junction, which Java walks into
     * like a plain directory (#499). Skips the test when neither can be made.
     */
    public static void directoryLinkOrSkip(Path link, Path target) throws IOException, InterruptedException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (FileSystemException | UnsupportedOperationException e) {
            assumeTrue(isWindows(), "symbolic links not supported here: " + e.getMessage());
        }
        // Absolute cmd.exe (%ComSpec%), never looked up on PATH.
        var mklink = new ProcessBuilder(windowsShell(), "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        mklink.getInputStream().readAllBytes();
        assumeTrue(mklink.waitFor() == 0 && Files.isDirectory(link), "could not create a directory junction");
    }

    private static String windowsShell() {
        var comSpec = System.getenv("ComSpec");
        if (comSpec != null && Path.of(comSpec).isAbsolute()) return comSpec;
        return Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe")
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }
}
