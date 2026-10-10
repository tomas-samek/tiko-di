package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #507: Javalin 7 only accepts routes inside {@code Javalin.create(config -> ...)}, so every place
 * that teaches the HTTP recipe must show routes registering themselves there, never added to the
 * produced instance afterwards ({@code app.get(...)} / {@code app.post(...)} no longer compile).
 * The archetype copy of {@code tiko-build} is held to its canonical source by
 * {@link ArchetypeBundledSkillsInSyncTest}.
 */
class HttpRecipeTaughtForJavalin7Test {

    static Stream<Arguments> recipeDocs() {
        return Stream.of(
                Arguments.of(
                        "tiko-build SKILL.md",
                        ArchetypeDocSync.canonicalDir("tiko-build").resolve("SKILL.md")),
                Arguments.of(
                        "tiko-cookbook-extension SKILL.md",
                        ArchetypeDocSync.canonicalDir("tiko-cookbook-extension").resolve("SKILL.md")),
                Arguments.of("orchestrator-model.md", Path.of("..", "docs", "orchestrator-model.md")),
                Arguments.of("cookbooks/persistence.md", Path.of("..", "docs", "cookbooks", "persistence.md")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recipeDocs")
    void showsNoRoutesAddedAfterCreate(String name, Path doc) throws Exception {
        assertThat(Files.readString(doc))
                .doesNotContain("app.get(", "app.post(", "app.before(", "Routes registered in", "Routes register in");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recipeDocs")
    void showsRoutesRegisteringInsideCreate(String name, Path doc) throws Exception {
        assertThat(Files.readString(doc)).contains("cfg.routes");
    }
}
