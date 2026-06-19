package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift gate (#408): every archetype-bundled agent skill must equal
 * {@link ArchetypeDocSync#forArchetype} applied to its canonical repo-root source. Fails the build
 * the moment a bundled copy falls behind canonical, so the {@code #401}–{@code #406} silent drift
 * cannot recur. Regenerate with {@link ArchetypeDocSync#main}.
 */
class ArchetypeBundledSkillsInSyncTest {

    static List<String> syncedSkills() {
        return ArchetypeDocSync.SYNCED_SKILLS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syncedSkills")
    void bundledSkillIsInSyncWithCanonical(String skill) throws Exception {
        String expected = ArchetypeDocSync.forArchetype(Files.readString(ArchetypeDocSync.canonical(skill)));
        String actual = Files.readString(ArchetypeDocSync.bundled(skill));

        assertThat(normalize(actual))
                .as(
                        "Bundled .ai-skills/%s/SKILL.md has drifted from its canonical source. Regenerate it from"
                                + " the tiko-archetype/ directory: `mvn -q test-compile` then `java -cp target/test-classes"
                                + " io.tiko.archetype.ArchetypeDocSync`.",
                        skill)
                .isEqualTo(normalize(expected));
    }

    /** Compare line-ending-agnostically — the repo stores LF but Windows checkouts may be CRLF. */
    private static String normalize(String markdown) {
        return markdown.replace("\r\n", "\n");
    }
}
