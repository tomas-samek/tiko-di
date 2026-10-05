package io.tiko.kafka.processor;

import static org.assertj.core.api.Assertions.assertThat;

import javax.lang.model.SourceVersion;
import org.junit.jupiter.api.Test;

/**
 * The processor must claim the compiling JDK's latest source version. A fixed constant
 * (it used to be {@code RELEASE_21}) makes javac warn "Supported source version ... less
 * than -source" for every user compiling at a newer {@code --release}, which breaks
 * {@code -Werror} builds on each new JDK.
 */
class SupportedSourceVersionTest {

    @Test
    void supportsLatestSourceVersionOfCompilingJdk() {
        assertThat(new KafkaAnnotationProcessor().getSupportedSourceVersion())
                .isEqualTo(SourceVersion.latestSupported());
    }
}
