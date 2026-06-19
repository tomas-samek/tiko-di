package io.tiko.archetype;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives an archetype-bundled agent-skill doc from its canonical repo-root source (#408).
 *
 * <p>The archetype ships copies of the agent skills (e.g. {@code .ai-skills/tiko-build/SKILL.md})
 * so they land in a scaffolded project. Those copies silently drifted from the canonical
 * repo-root versions — the root cause of the {@code #401}–{@code #406} batch. This class is the
 * single source of truth for the one deterministic adaptation a bundled copy carries: repo-relative
 * {@code ../../} links (which don't resolve inside a scaffolded project) become absolute GitHub
 * URLs. Sibling {@code ../} links (other bundled skills, which DO ship alongside) are left intact;
 * everything else is byte-identical to canonical.
 *
 * <p>{@code ArchetypeBundledSkillsInSyncTest} enforces {@code bundled == forArchetype(canonical)};
 * {@link #main} regenerates the bundled copies when canonical changes. The bundled skill filesets
 * are {@code filtered="false"} in the archetype descriptor, so literal {@code ${...}} in the docs
 * passes through Velocity untouched and needs no escaping.
 */
public final class ArchetypeDocSync {

    /** Skills bundled by the archetype that are copies of a canonical repo-root skill. */
    public static final List<String> SYNCED_SKILLS = List.of("tiko-build", "tiko-cookbook-extension");

    private static final String GITHUB = "https://github.com/tomas-samek/tiko-di";

    /** A markdown link target of the form {@code ](../../<path>)}. */
    private static final Pattern REPO_RELATIVE = Pattern.compile("]\\(\\.\\./\\.\\./([^)]+)\\)");

    private ArchetypeDocSync() {}

    /** Applies the archetype adaptation (repo-relative links → absolute GitHub URLs) to canonical markdown. */
    public static String forArchetype(String canonical) {
        Matcher m = REPO_RELATIVE.matcher(canonical);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            // A trailing path segment containing a dot is a file (GitHub /blob/); otherwise a directory (/tree/).
            String kind = lastSegment.contains(".") ? "blob" : "tree";
            m.appendReplacement(out, Matcher.quoteReplacement("](" + GITHUB + "/" + kind + "/main/" + path + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Path to a canonical skill, relative to the {@code tiko-archetype} module directory. */
    public static Path canonical(String skill) {
        return Path.of("..", ".ai-skills", skill, "SKILL.md");
    }

    /** Path to the archetype-bundled copy of a skill, relative to the {@code tiko-archetype} module directory. */
    public static Path bundled(String skill) {
        return Path.of("src", "main", "resources", "archetype-resources", ".ai-skills", skill, "SKILL.md");
    }

    /** Regenerates every bundled skill from its canonical source. Run from the {@code tiko-archetype} directory. */
    public static void main(String[] args) throws IOException {
        for (String skill : SYNCED_SKILLS) {
            String derived = forArchetype(Files.readString(canonical(skill)));
            Files.writeString(bundled(skill), derived);
            System.out.println("regenerated " + bundled(skill));
        }
    }
}
