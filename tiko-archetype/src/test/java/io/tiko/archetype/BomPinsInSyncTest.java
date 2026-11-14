package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * #701: {@code tiko-bom} has no parent, so it repeats version properties of the root pom instead of
 * inheriting them. A bump that touches only the root leaves the BOM building, and at a release
 * publishing, with the old plugin.
 */
class BomPinsInSyncTest {

    /** Paths relative to the {@code tiko-archetype} module directory. */
    private static final Path ROOT_POM = Path.of("..", "pom.xml");

    private static final Path BOM_POM = Path.of("..", "tiko-bom", "pom.xml");

    private static final Pattern VERSION_PROPERTY = Pattern.compile("<([\\w.-]+\\.version)>([^<]+)</\\1>");

    @Test
    void everyVersionPropertyTheBomRepeatsMatchesTheRootPom() throws IOException {
        var root = versionProperties(ROOT_POM);
        var repeated = versionProperties(BOM_POM);
        repeated.keySet().retainAll(root.keySet());

        assertThat(repeated)
                .as("the BOM repeats version properties of the root pom")
                .isNotEmpty();
        assertThat(repeated)
                .as("version properties tiko-bom repeats from the root pom")
                .containsExactlyInAnyOrderEntriesOf(matching(root, repeated));
    }

    /** The root pom's values for the properties {@code repeated} names. */
    private static Map<String, String> matching(Map<String, String> root, Map<String, String> repeated) {
        var expected = new TreeMap<String, String>();
        for (var name : repeated.keySet()) {
            expected.put(name, root.get(name));
        }
        return expected;
    }

    private static Map<String, String> versionProperties(Path pom) throws IOException {
        var properties = new TreeMap<String, String>();
        var matcher = VERSION_PROPERTY.matcher(Files.readString(pom));
        while (matcher.find()) {
            properties.put(matcher.group(1), matcher.group(2).strip());
        }
        return properties;
    }
}
