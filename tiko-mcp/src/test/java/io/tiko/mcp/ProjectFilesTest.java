package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ProjectFiles} without symbolic links, so it runs on every OS (#475). */
class ProjectFilesTest {

    @Test
    void fileUnderTheRootIsInside(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        var file =
                Files.writeString(Files.createDirectories(root.resolve("a/b")).resolve("x.json"), "{}");

        assertThat(ProjectFiles.isInside(ProjectFiles.realRoot(root), file)).isTrue();
    }

    @Test
    void siblingDirectoryWithACommonPrefixIsOutside(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        var sibling = Files.writeString(
                Files.createDirectories(tmp.resolve("project-other")).resolve("x.json"), "{}");

        assertThat(ProjectFiles.isInside(ProjectFiles.realRoot(root), sibling))
                .as("path containment, not string prefix")
                .isFalse();
    }

    @Test
    void unresolvableCandidateIsTreatedAsOutside(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));

        assertThat(ProjectFiles.isInside(ProjectFiles.realRoot(root), root.resolve("missing.json")))
                .as("a path whose real location can't be resolved is never trusted")
                .isFalse();
    }

    @Test
    void aDirectoryIsEnteredOnlyOnceByItsRealPath(@TempDir Path tmp) throws Exception {
        var dir = Files.createDirectories(tmp.resolve("a"));
        var entered = new java.util.HashSet<Path>();

        assertThat(ProjectFiles.enterOnce(entered, dir)).isEqualTo(java.nio.file.FileVisitResult.CONTINUE);
        assertThat(ProjectFiles.enterOnce(entered, tmp.resolve("a/../a")))
                .as("the same real directory reached again (#499)")
                .isEqualTo(java.nio.file.FileVisitResult.SKIP_SUBTREE);
        assertThat(ProjectFiles.enterOnce(entered, tmp.resolve("missing")))
                .isEqualTo(java.nio.file.FileVisitResult.SKIP_SUBTREE);
    }

    @Test
    void findReturnsMatchesInsideTheRootAndCanStopAtTheFirst(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(Files.createDirectories(root.resolve("a")).resolve("x.json"), "{}");
        Files.writeString(Files.createDirectories(root.resolve("b")).resolve("x.json"), "{}");
        Files.writeString(root.resolve("other.txt"), "");

        java.util.function.Predicate<Path> json =
                p -> p.getFileName().toString().equals("x.json");
        assertThat(ProjectFiles.find(root, json, false)).hasSize(2);
        assertThat(ProjectFiles.find(root, json, true)).hasSize(1);
        assertThat(ProjectFiles.find(root.resolve("missing"), json, false)).isEmpty();
    }

    @Test
    void unresolvableRootFallsBackToItsNormalizedAbsolutePath(@TempDir Path tmp) {
        var missingRoot = tmp.resolve("not-there/../project");

        assertThat(ProjectFiles.realRoot(missingRoot))
                .isEqualTo(missingRoot.toAbsolutePath().normalize());
    }
}
