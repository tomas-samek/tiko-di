package io.tiko.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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

    /**
     * Regular files under {@code root} that match {@code matches} and whose real path is inside
     * the project, in walk order; stops after the first when {@code firstOnly}. Each directory is
     * entered once by its real path, so a link back to an ancestor (a Windows junction is walked
     * like a plain directory) can't loop the walk (#499). Unreadable entries are skipped.
     */
    public static List<Path> find(Path root, Predicate<Path> matches, boolean firstOnly) {
        var found = new ArrayList<Path>();
        if (!Files.isDirectory(root)) return found;
        Path realRoot = realRoot(root);
        Set<Path> enteredDirectories = new HashSet<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return enterOnce(enteredDirectories, dir);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // A matching name that is a link leading outside the project is skipped (#475).
                    if (matches.test(file) && isInside(realRoot, file)) {
                        found.add(file);
                        if (firstOnly) return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /** Enter {@code dir} only the first time its real path is seen; skip it if unresolvable. */
    static FileVisitResult enterOnce(Set<Path> entered, Path dir) {
        try {
            return entered.add(dir.toRealPath()) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
        } catch (IOException e) {
            return FileVisitResult.SKIP_SUBTREE;
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
