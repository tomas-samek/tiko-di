package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #703: what a release publishes beyond the main, sources and javadoc jars. A {@code test-jar} is an
 * attached artifact, and the publishing plugin stages every attached artifact of a published module.
 * A shaded jar's {@code NOTICE} is written by a transformer that credits the Apache Software Foundation
 * unless it is told who the project's copyright holder is.
 */
class PublishedArtifactsTest {

    /** Module directories relative to the {@code tiko-archetype} module directory. */
    static Stream<Path> modulePoms() throws IOException {
        try (var modules = Files.list(Path.of(".."))) {
            List<Path> poms = modules.filter(dir -> dir.getFileName().toString().startsWith("tiko-"))
                    .map(dir -> dir.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
            return poms.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modulePoms")
    void moduleAttachesNoTestJar(Path pom) throws IOException {
        assertThat(Files.readString(pom))
                .as("%s attaches a test-jar, which a release would publish; share test helpers as sources", pom)
                .doesNotContain("<goal>test-jar</goal>");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modulePoms")
    void shadedNoticeNamesTheProjectsCopyrightHolder(Path pom) throws IOException {
        var text = Files.readString(pom);
        if (!text.contains("ApacheNoticeResourceTransformer")) {
            return;
        }

        assertThat(text)
                .as("%s: the NOTICE transformer defaults to the Apache Software Foundation, since 2006", pom)
                .contains("<organizationName>Tomas Samek</organizationName>")
                .contains("<inceptionYear>2025</inceptionYear>");
    }
}
