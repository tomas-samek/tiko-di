package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code release.yml} passes setup-java its credential and passphrase variable names through the
 * current {@code *-env-var} inputs, not the aliases setup-java v6 deprecated. A release is the only
 * run that exercises these inputs, so an alias dropped by a future setup-java major would otherwise
 * surface as a failed release rather than a red PR.
 */
class ReleaseWorkflowInputsTest {

    /** Relative to the {@code tiko-archetype} module directory. */
    private static final Path RELEASE = Path.of("..", ".github", "workflows", "release.yml");

    static Stream<String> deprecatedAliases() {
        return Stream.of("server-username", "server-password", "gpg-passphrase");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deprecatedAliases")
    void setupJavaUsesTheEnvVarInputNotTheDeprecatedAlias(String alias) throws IOException {
        var workflow = Files.readString(RELEASE);

        assertThat(workflow)
                .as("release.yml should use setup-java's '%s-env-var' input; '%s' is a deprecated alias", alias, alias)
                .doesNotContainPattern("(?m)^\\s+" + alias + ":")
                .containsPattern("(?m)^\\s+" + alias + "-env-var:\\s*\\S+");
    }
}
