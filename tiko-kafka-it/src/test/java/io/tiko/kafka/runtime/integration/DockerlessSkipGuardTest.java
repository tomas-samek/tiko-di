package io.tiko.kafka.runtime.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression guard for #352: the real-broker IT must declare
 * {@code @Testcontainers(disabledWithoutDocker = true)} so a machine without Docker
 * reports the IT as skipped instead of hard-failing {@code mvn verify}. Runs in the
 * surefire (unit) lane — no Docker required — so the guard itself is always evaluated.
 */
class DockerlessSkipGuardTest {

    @Test
    void realBrokerItIsDisabledWithoutDocker() {
        Testcontainers annotation = KafkaRealBrokerRoundTripIT.class.getAnnotation(Testcontainers.class);

        assertThat(annotation)
                .as("KafkaRealBrokerRoundTripIT must be annotated with @Testcontainers")
                .isNotNull();
        assertThat(annotation.disabledWithoutDocker())
                .as("the real-broker IT must skip (not fail) when Docker is unavailable")
                .isTrue();
    }
}
