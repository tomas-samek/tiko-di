package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift gate (#408): every archetype-bundled agent-skill directory must mirror
 * {@link ArchetypeDocSync#forArchetype} applied to its canonical repo-root source, file for file.
 * Fails the build the moment a bundled copy falls behind canonical (missing/extra files or
 * drifted content), so the {@code #401}–{@code #406} silent drift cannot recur. Regenerate with
 * {@link ArchetypeDocSync#main}.
 */
class ArchetypeBundledSkillsInSyncTest {

    static List<String> syncedSkills() {
        return ArchetypeDocSync.SYNCED_SKILLS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syncedSkills")
    void bundledSkillDirectoryIsInSyncWithCanonical(String skill) throws Exception {
        Path canonicalDir = ArchetypeDocSync.canonicalDir(skill);
        Path bundledDir = ArchetypeDocSync.bundledDir(skill);
        List<Path> canonicalFiles = ArchetypeDocSync.markdownFiles(canonicalDir);
        List<Path> bundledFiles = ArchetypeDocSync.markdownFiles(bundledDir);

        assertThat(bundledFiles)
                .as("bundled %s markdown set must mirror canonical (regenerate via ArchetypeDocSync.main)", skill)
                .containsExactlyElementsOf(canonicalFiles);

        for (Path rel : canonicalFiles) {
            String expected = ArchetypeDocSync.forArchetype(Files.readString(canonicalDir.resolve(rel)));
            String actual = Files.readString(bundledDir.resolve(rel));
            assertThat(normalize(actual))
                    .as(
                            "Bundled .ai-skills/%s/%s has drifted from canonical. Regenerate from the"
                                    + " tiko-archetype/ directory: `mvn -q test-compile` then `java -cp"
                                    + " target/test-classes io.tiko.archetype.ArchetypeDocSync`.",
                            skill, rel)
                    .isEqualTo(normalize(expected));
        }
    }

    /** Compare line-ending-agnostically — the repo stores LF but Windows checkouts may be CRLF. */
    private static String normalize(String markdown) {
        return markdown.replace("\r\n", "\n");
    }
}
