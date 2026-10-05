package io.tiko.mcp;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Confines {@code tiko-mcp} file access to the project root (#475). A file counts as part of the
 * project only if its real path — every symbolic link resolved — lies under the root's real path,
 * so a link inside the project can't lead a tool to a file outside it.
 */
public final class ProjectFiles {

    private ProjectFiles() {}

    /** The root with symbolic links resolved; the normalized absolute path if it can't be resolved. */
    public static Path realRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /** {@code true} if {@code candidate}'s real path is under {@code realRoot}; {@code false} if it can't be resolved. */
    public static boolean isInside(Path realRoot, Path candidate) {
        try {
            return candidate.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }
}
