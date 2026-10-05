package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Checks on {@code SECURITY.md}, the policy GitHub shows on the repository's Security tab. */
class SecurityPolicyTest {

    /** Relative to the {@code tiko-archetype} module directory. */
    private static final Path POLICY = Path.of("..", "SECURITY.md");

    /** A version line such as {@code 0.5.x} or {@code 1.2.3}. */
    private static final Pattern VERSION_LINE = Pattern.compile("\\b\\d+\\.\\d+\\.(?:x|\\d+)\\b");

    @Test
    void supportedVersionsNameNoVersionThatGoesStaleAtTheNextRelease() throws IOException {
        var policy = Files.readString(POLICY);
        var start = policy.indexOf("## Supported versions");
        var end = policy.indexOf("\n## ", start + 1);
        assertThat(start)
                .as("SECURITY.md must have a '## Supported versions' section")
                .isNotNegative();

        var section = policy.substring(start, end < 0 ? policy.length() : end);

        assertThat(VERSION_LINE.matcher(section).results().map(m -> m.group()).toList())
                .as("A concrete version in SECURITY.md's supported-versions section names an unsupported line as"
                        + " 'latest' as soon as the next minor ships. Describe the supported line relative to the"
                        + " latest release instead.")
                .isEmpty();
    }
}
