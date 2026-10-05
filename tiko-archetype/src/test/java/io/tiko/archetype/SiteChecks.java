package io.tiko.archetype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Parsing behind {@link SiteInSyncTest}, kept separate so it can be tested against inline fixtures. */
final class SiteChecks {

    private static final String WORKFLOW = ".github/workflows/maven.yml";

    /** {@code href}/{@code src}/… whose value — quoted or not — starts with a single {@code /}. */
    private static final Pattern ROOT_RELATIVE_ATTRIBUTE =
            Pattern.compile("\\b(?:href|src|action|poster)\\s*=\\s*[\"']?/(?!/)");

    private static final Pattern SRCSET = Pattern.compile("\\bsrcset\\s*=\\s*([\"'])([^\"']*)\\1");
    private static final Pattern ROOT_RELATIVE_CSS_URL = Pattern.compile("url\\(\\s*[\"']?/(?!/)");
    private static final Pattern ROOT_RELATIVE_CSS_IMPORT = Pattern.compile("@import\\s+[\"']/(?!/)");

    private SiteChecks() {}

    /**
     * The JDK versions in the {@code build} job's {@code java} matrix of a GitHub Actions workflow. Reads the
     * inline ({@code java: [ '21', "25", 26 ]}) and block-list forms, and never looks past the build job.
     */
    static List<String> buildJobJdks(String workflow) {
        var lines = workflow.lines().toList();
        var job = lines.indexOf("  build:");
        if (job < 0) {
            throw new IllegalStateException("No `build:` job in " + WORKFLOW);
        }
        for (var i = job + 1; i < lines.size() && !isJobKey(lines.get(i)); i++) {
            var line = lines.get(i).strip();
            if (line.startsWith("java:")) {
                var value = line.substring("java:".length()).strip();
                return value.isEmpty() ? blockList(lines, i + 1) : inlineList(value);
            }
        }
        throw new IllegalStateException("The build job in " + WORKFLOW + " has no `java:` matrix");
    }

    /** Root-relative ({@code /…}) references in the page and its stylesheet; each would 404 under {@code /tiko-di/}. */
    static List<String> rootRelativeReferences(String html, String css) {
        var found = new ArrayList<String>();
        addMatches(ROOT_RELATIVE_ATTRIBUTE, html, found);
        var srcset = SRCSET.matcher(html);
        while (srcset.find()) {
            Arrays.stream(srcset.group(2).split(","))
                    .map(String::strip)
                    .filter(candidate -> candidate.startsWith("/") && !candidate.startsWith("//"))
                    .forEach(found::add);
        }
        addMatches(ROOT_RELATIVE_CSS_URL, css, found);
        addMatches(ROOT_RELATIVE_CSS_IMPORT, css, found);
        return found;
    }

    /** A top-level key under {@code jobs:} — two-space indent, not a comment. */
    private static boolean isJobKey(String line) {
        return line.length() > 2
                && line.startsWith("  ")
                && !Character.isWhitespace(line.charAt(2))
                && line.charAt(2) != '#';
    }

    private static List<String> inlineList(String value) {
        var open = value.indexOf('[');
        var close = value.indexOf(']', open + 1);
        if (open < 0 || close < 0) {
            throw new IllegalStateException("Unreadable build-job `java:` matrix in " + WORKFLOW + ": " + value);
        }
        return Arrays.stream(value.substring(open + 1, close).split(","))
                .map(SiteChecks::unquote)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private static List<String> blockList(List<String> lines, int from) {
        var entries = new ArrayList<String>();
        for (var i = from; i < lines.size(); i++) {
            var line = lines.get(i).strip();
            if (line.startsWith("-")) {
                entries.add(unquote(line.substring(1)));
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                break;
            }
        }
        return entries;
    }

    private static String unquote(String entry) {
        var value = entry.strip();
        var quoted = value.length() >= 2
                && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0);
        return quoted ? value.substring(1, value.length() - 1) : value;
    }

    private static void addMatches(Pattern pattern, String text, List<String> found) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
    }
}
