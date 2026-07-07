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
 * everything else is byte-identical to canonical, every {@code *.md} under the skill directory.
 *
 * <p>{@code ArchetypeBundledSkillsInSyncTest} enforces {@code bundled == forArchetype(canonical)};
 * {@link #main} regenerates the bundled copies when canonical changes. The bundled skill filesets
 * are {@code filtered="false"} in the archetype descriptor, so literal {@code ${...}} in the docs
 * passes through Velocity untouched and needs no escaping.
 */
public final class ArchetypeDocSync {

    /** Skills bundled by the archetype that are copies of a canonical repo-root skill. */
    public static final List<String> SYNCED_SKILLS = List.of("tiko-build");

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

    /** Canonical skill directory, relative to the {@code tiko-archetype} module directory. */
    public static Path canonicalDir(String skill) {
        return Path.of("..", ".ai-skills", skill);
    }

    /** Bundled skill directory, relative to the {@code tiko-archetype} module directory. */
    public static Path bundledDir(String skill) {
        return Path.of("src", "main", "resources", "archetype-resources", ".ai-skills", skill);
    }

    /** Every markdown file under {@code root}, as sorted paths relative to {@code root}. */
    public static List<Path> markdownFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".md"))
                    .map(root::relativize)
                    .sorted()
                    .toList();
        }
    }

    /** Regenerates every bundled skill directory from its canonical source. Run from {@code tiko-archetype}. */
    public static void main(String[] args) throws IOException {
        for (String skill : SYNCED_SKILLS) {
            Path from = canonicalDir(skill);
            Path to = bundledDir(skill);
            // Remove bundled markdown with no canonical counterpart (renames/deletions propagate).
            for (Path stale : markdownFiles(to)) {
                if (!Files.exists(from.resolve(stale))) {
                    Files.delete(to.resolve(stale));
                    System.out.println("deleted stale " + to.resolve(stale));
                }
            }
            for (Path rel : markdownFiles(from)) {
                Path target = to.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.writeString(target, forArchetype(Files.readString(from.resolve(rel))));
                System.out.println("regenerated " + target);
            }
        }
    }
}
