package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * #688: an agent writing a Kafka consumer from the agent-facing docs alone must learn that a
 * throwing downstream handler does not redeliver the record, and where work that must be
 * redelivered goes. Without it, moving a write from the bridge into a handler reads as a harmless
 * refactor.
 */
class AgentFacingKafkaCommitBoundaryGuidanceTest {

    @Test
    void kafkaReferenceSaysHandlerFailuresCommitAndWhereRedeliveredWorkGoes() throws Exception {
        var doc = ArchetypeDocSync.canonicalDir("tiko-build").resolve("reference/kafka.md");

        assertThat(Files.readString(doc))
                .contains("the offset is committed anyway", "goes in the bridge method", "idempotent");
    }
}
