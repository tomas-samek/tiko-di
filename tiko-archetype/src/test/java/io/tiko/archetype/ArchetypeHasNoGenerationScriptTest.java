package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards #557: {@code mvn archetype:generate} runs an archetype's post-generation script on the
 * Groovy bundled with whichever {@code maven-archetype-plugin} the user's Maven resolves. Our build
 * can't pin that, and plugin 3.4.1's Groovy can't read JDK 26 class files, so generation failed
 * half-way on JDK 26+. The archetype ships every file under its final name instead (see
 * {@link GeneratedProjectDotfilesIT}).
 */
class ArchetypeHasNoGenerationScriptTest {

    @Test
    void archetypeShipsNoPostGenerateScript() {
        assertThat(Path.of("src", "main", "resources", "META-INF", "archetype-post-generate.groovy"))
                .as("a generation script runs on the user's plugin Groovy, which breaks on new JDKs")
                .doesNotExist();
    }
}
