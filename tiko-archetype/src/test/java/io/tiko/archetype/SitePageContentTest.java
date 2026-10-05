package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Content checks on the landing page ({@code site/index.html}) that aren't about drift from another file. */
class SitePageContentTest {

    /** Relative to the {@code tiko-archetype} module directory. */
    private static final Path PAGE = Path.of("..", "site", "index.html");

    @Test
    void topHeadingNamesTheProduct() throws IOException {
        var page = Files.readString(PAGE);
        var start = page.indexOf("<h1");
        var end = page.indexOf("</h1>", start);
        assertThat(start).as("site/index.html must have an h1").isNotNegative();

        var headingText = page.substring(page.indexOf('>', start) + 1, end).replaceAll("<[^>]+>", "");

        assertThat(headingText)
                .as("The h1 is what screen-reader heading navigation and search results show; it must name Tiko")
                .contains("Tiko");
    }
}
