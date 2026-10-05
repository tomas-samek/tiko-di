package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift gate for the landing page ({@code site/index.html}). The page states the released BOM version and
 * the supported JDKs as literal text; both go stale silently. JDK support policy: every LTS from 21 onward
 * plus every GA since the latest LTS — the page, the README Java badge and the CI build matrix must list the
 * same set.
 */
class SiteInSyncTest {

    /** Paths relative to the {@code tiko-archetype} module directory. */
    private static final Path PAGE = Path.of("..", "site", "index.html");

    private static final Path STYLESHEET = Path.of("..", "site", "style.css");
    private static final Path README = Path.of("..", "README.md");
    private static final Path CI_WORKFLOW = Path.of("..", ".github", "workflows", "maven.yml");

    private static final Pattern README_BOM_VERSION =
            Pattern.compile("<artifactId>tiko-bom</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern README_JAVA_BADGE = Pattern.compile("img\\.shields\\.io/badge/Java-([^-]+)-");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    @Test
    void bomVersionMatchesReadme() throws IOException {
        var pageVersion =
                SitePage.section(Files.readString(PAGE), "bom-version").strip();

        assertThat(pageVersion)
                .as("site/index.html install snippet advertises a different tiko-bom version than README.md."
                        + " Bump both on release (tiko-release skill, Step 6).")
                .isEqualTo(firstGroup(README_BOM_VERSION, Files.readString(README)));
    }

    static Stream<Arguments> jdkListings() throws IOException {
        var page = Files.readString(PAGE);
        var readme = Files.readString(README);
        var badge = URLDecoder.decode(firstGroup(README_JAVA_BADGE, readme), StandardCharsets.UTF_8);
        var pageListings = SitePage.sections(page, "jdks");
        assertThat(pageListings)
                .as("site/index.html must state the JDK list in the hero and the install section")
                .hasSizeGreaterThanOrEqualTo(2);
        var rows = IntStream.range(0, pageListings.size())
                .mapToObj(i -> Arguments.of(pageJdkRowName(i), split(pageListings.get(i), "·")));
        return Stream.concat(rows, Stream.of(Arguments.of("README Java badge", split(badge, "\\|"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jdkListings")
    void jdkListMatchesCiBuildMatrix(String where, List<String> jdks) throws IOException {
        var matrix = SiteChecks.buildJobJdks(Files.readString(CI_WORKFLOW));

        assertThat(jdks)
                .as(
                        "%s lists different JDKs than the build matrix in .github/workflows/maven.yml. Policy: every"
                                + " LTS from 21 onward plus every GA since the latest LTS; update all three together.",
                        where)
                .isEqualTo(matrix);
    }

    @Test
    void jdkRowsNameWhereTheListLives() throws IOException {
        var names = jdkListings().map(row -> (String) row.get()[0]).toList();

        assertThat(names)
                .as("Each JDK-list row must name its own location, so a failure says which one drifted")
                .doesNotHaveDuplicates();
    }

    @Test
    void installSnippetListsTheReadmeArtifacts() throws IOException {
        var pageSnippet = SitePage.section(Files.readString(PAGE), "install");

        assertThat(artifactIds(pageSnippet))
                .as("site/index.html install snippet lists different artifacts than README.md's Installation"
                        + " snippet. A visitor copying the page's snippet must get everything the README lists.")
                .isEqualTo(artifactIds(readmeInstallSnippet(Files.readString(README))));
    }

    @Test
    void pageUsesNoRootRelativeUrls() throws IOException {
        assertThat(SiteChecks.rootRelativeReferences(Files.readString(PAGE), Files.readString(STYLESHEET)))
                .as("The site is served under /tiko-di/, so a root-relative reference (\"/...\") would 404."
                        + " Use a relative path.")
                .isEmpty();
    }

    /** The page states the JDK list in the hero first, then in the install section. */
    private static String pageJdkRowName(int index) {
        var where =
                switch (index) {
                    case 0 -> "hero";
                    case 1 -> "install section";
                    default -> "occurrence " + (index + 1);
                };
        return "site/index.html " + where + " (sync:jdks #" + (index + 1) + ")";
    }

    /** The first {@code ```xml} block after README's {@code ## Installation} heading. */
    private static String readmeInstallSnippet(String readme) {
        var heading = readme.indexOf("## Installation");
        var open = heading < 0 ? -1 : readme.indexOf("```xml", heading);
        var close = open < 0 ? -1 : readme.indexOf("```", open + "```xml".length());
        if (close < 0) {
            throw new IllegalStateException("No ```xml block under '## Installation' in README.md");
        }
        return readme.substring(open, close);
    }

    private static List<String> artifactIds(String xml) {
        var matcher = ARTIFACT_ID.matcher(xml);
        var ids = new ArrayList<String>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String firstGroup(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("Pattern not found: " + pattern.pattern());
        }
        return matcher.group(1);
    }

    private static List<String> split(String list, String separator) {
        return Arrays.stream(list.split(separator))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
