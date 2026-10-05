package io.tiko.archetype;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads {@code <!-- sync:NAME -->} … {@code <!-- /sync:NAME -->} blocks out of {@code site/index.html} as
 * plain text: tags stripped, the four entities the page uses unescaped.
 */
final class SitePage {

    private SitePage() {}

    /** The single block called {@code name}; fails if it's missing or duplicated, so a test can't pass vacuously. */
    static String section(String page, String name) {
        var found = sections(page, name);
        if (found.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one sync:" + name + " block in site/index.html, found " + found.size());
        }
        return found.get(0);
    }

    static List<String> sections(String page, String name) {
        var quoted = Pattern.quote(name);
        var matcher = Pattern.compile("<!-- sync:" + quoted + " -->(.*?)<!-- /sync:" + quoted + " -->", Pattern.DOTALL)
                .matcher(page);
        var found = new ArrayList<String>();
        while (matcher.find()) {
            found.add(toText(matcher.group(1)));
        }
        return found;
    }

    /** Trailing whitespace per line and surrounding blank lines removed; line endings normalised to {@code \n}. */
    static String normalize(String text) {
        return text.lines()
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static String toText(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }
}
