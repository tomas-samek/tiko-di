package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift gate (#460): the "read when" trigger table for the {@code tiko-build} reference chunks is
 * stated twice — canonically in {@code .ai-skills/tiko-build/SKILL.md}, and again in the
 * archetype-generated {@code CLAUDE.md} that ships into every scaffolded project. Both are live
 * agent-facing content, and an agent's decision to open a chunk depends on whichever copy it read,
 * so the two must not disagree about when to open a given chunk.
 *
 * <p>The two tables are deliberately not byte-identical — the archetype's link targets are rewritten
 * to {@code ./.ai-skills/tiko-build/...} and it carries an extra row pointing at {@code SKILL.md}
 * itself — so {@link ArchetypeBundledSkillsInSyncTest}'s whole-file comparison cannot cover them.
 * This gate compares the trigger text per reference chunk instead, keyed by the chunk's link text.
 */
class ArchetypeTriggerTableInSyncTest {

    /** Canonical {@code SKILL.md}, relative to the {@code tiko-archetype} module directory. */
    private static final Path CANONICAL_SKILL =
            ArchetypeDocSync.canonicalDir("tiko-build").resolve("SKILL.md");

    /** Archetype-generated {@code CLAUDE.md}, relative to the {@code tiko-archetype} module directory. */
    private static final Path ARCHETYPE_CLAUDE_MD =
            Path.of("src", "main", "resources", "archetype-resources", "CLAUDE.md");

    /** A markdown table row whose first cell is a link with a code-span label: {@code | [`label`](target) | text |}. */
    private static final Pattern TRIGGER_ROW =
            Pattern.compile("^\\|\\s*\\[`([^`]+)`]\\([^)]*\\)\\s*\\|\\s*(.+?)\\s*\\|\\s*$");

    /** Only rows describing a {@code reference/} chunk are shared between the two tables. */
    private static final String CHUNK_PREFIX = "reference/";

    static Stream<Arguments> sharedChunks() throws IOException {
        Map<String, String> canonical = triggers(CANONICAL_SKILL);
        Map<String, String> archetype = triggers(ARCHETYPE_CLAUDE_MD);
        return canonical.entrySet().stream()
                .map(e -> Arguments.of(e.getKey(), e.getValue(), archetype.get(e.getKey())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedChunks")
    void archetypeTriggerMatchesCanonical(String chunk, String canonicalTrigger, String archetypeTrigger) {
        assertThat(archetypeTrigger)
                .as(
                        "The archetype CLAUDE.md trigger for %s disagrees with the canonical one in"
                                + " .ai-skills/tiko-build/SKILL.md. Both tables are agent-facing; an agent must not"
                                + " get a different answer about when to open a chunk depending on which copy it"
                                + " read. Update tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md to"
                                + " match SKILL.md, which is canonical.",
                        chunk)
                .isEqualTo(canonicalTrigger);
    }

    @Test
    void bothTablesCoverTheSameChunks() throws IOException {
        assertThat(triggers(ARCHETYPE_CLAUDE_MD).keySet())
                .as("The two trigger tables must list the same reference chunks — a chunk added to"
                        + " .ai-skills/tiko-build/SKILL.md must also be listed in the archetype CLAUDE.md.")
                .containsExactlyInAnyOrderElementsOf(triggers(CANONICAL_SKILL).keySet());
    }

    /** Trigger text per reference chunk, keyed by the chunk's link label, in document order. */
    private static Map<String, String> triggers(Path markdown) throws IOException {
        var found = new LinkedHashMap<String, String>();
        for (String line : Files.readString(markdown).split("\r?\n")) {
            Matcher m = TRIGGER_ROW.matcher(line);
            if (m.matches() && m.group(1).startsWith(CHUNK_PREFIX)) {
                found.put(m.group(1), m.group(2));
            }
        }
        assertThat(found)
                .as(
                        "No reference-chunk trigger rows parsed out of %s — the table shape changed and this"
                                + " gate is no longer reading it.",
                        markdown)
                .isNotEmpty();
        return found;
    }
}
