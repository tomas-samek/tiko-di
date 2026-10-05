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

    private static final Path README = Path.of("..", "README.md");
    private static final Path CI_WORKFLOW = Path.of("..", ".github", "workflows", "maven.yml");

    private static final Pattern README_BOM_VERSION =
            Pattern.compile("<artifactId>tiko-bom</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern README_JAVA_BADGE = Pattern.compile("img\\.shields\\.io/badge/Java-([^-]+)-");
    /** The {@code build} job's key line; its matrix is the first {@code java: [...]} line after it. */
    private static final Pattern CI_BUILD_JOB = Pattern.compile("(?m)^  build:");

    private static final Pattern CI_JAVA_MATRIX = Pattern.compile("(?m)^[ \\t]+java: \\[([^\\]\\r\\n]*)]");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern ROOT_RELATIVE_URL = Pattern.compile("(?:href|src)=\"/(?!/)");

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
        var rows = pageListings.stream().map(listing -> Arguments.of("site/index.html sync:jdks", split(listing, "·")));
        return Stream.concat(rows, Stream.of(Arguments.of("README Java badge", split(badge, "\\|"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jdkListings")
    void jdkListMatchesCiBuildMatrix(String where, List<String> jdks) throws IOException {
        var matrix = split(buildJobMatrix(Files.readString(CI_WORKFLOW)).replace("'", ""), ",");

        assertThat(jdks)
                .as(
                        "%s lists different JDKs than the build matrix in .github/workflows/maven.yml. Policy: every"
                                + " LTS from 21 onward plus every GA since the latest LTS; update all three together.",
                        where)
                .isEqualTo(matrix);
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
        assertThat(ROOT_RELATIVE_URL.matcher(Files.readString(PAGE)).find())
                .as("The site is served under /tiko-di/, so a root-relative href/src (\"/...\") would 404."
                        + " Use a relative path.")
                .isFalse();
    }

    private static String buildJobMatrix(String workflow) {
        var job = CI_BUILD_JOB.matcher(workflow);
        if (!job.find()) {
            throw new IllegalStateException("No build job in .github/workflows/maven.yml");
        }
        var matrix = CI_JAVA_MATRIX.matcher(workflow);
        if (!matrix.find(job.end())) {
            throw new IllegalStateException("No java: [...] matrix after the build job in .github/workflows/maven.yml");
        }
        return matrix.group(1);
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
