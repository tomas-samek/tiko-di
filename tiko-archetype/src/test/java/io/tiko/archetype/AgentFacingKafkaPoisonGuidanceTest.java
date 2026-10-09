package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #459: an agent configuring a Kafka consumer from the agent-facing docs alone must learn that the
 * default {@code SEEK} policy blocks a partition on a bad record, why that is the default, and that
 * {@code KafkaIngestErrorDecider} expresses bounded-retry-then-dead-letter. Without it, agents
 * overrode the policy to {@code SKIP} unprompted.
 */
class AgentFacingKafkaPoisonGuidanceTest {

    static Stream<Arguments> agentFacingDocs() {
        return Stream.of(
                Arguments.of(
                        "canonical tiko-build reference/kafka.md",
                        ArchetypeDocSync.canonicalDir("tiko-build").resolve("reference/kafka.md"),
                        List.of(
                                "poison-record-policy",
                                "SEEK",
                                "blocks",
                                "transient",
                                "KafkaIngestErrorDecider",
                                "DEAD_LETTER",
                                "KafkaRecordDeadLettered")),
                Arguments.of(
                        "archetype CLAUDE.md",
                        Path.of("src", "main", "resources", "archetype-resources", "CLAUDE.md"),
                        List.of("poison-record-policy", "SEEK", "KafkaIngestErrorDecider")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("agentFacingDocs")
    void namesTheSeekDefaultAndTheDecider(String name, Path doc, List<String> phrases) throws Exception {
        assertThat(Files.readString(doc)).contains(phrases);
    }
}
