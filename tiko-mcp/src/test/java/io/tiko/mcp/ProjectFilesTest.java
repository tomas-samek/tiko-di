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
    void unresolvableRootFallsBackToItsNormalizedAbsolutePath(@TempDir Path tmp) {
        var missingRoot = tmp.resolve("not-there/../project");

        assertThat(ProjectFiles.realRoot(missingRoot))
                .isEqualTo(missingRoot.toAbsolutePath().normalize());
    }
}
