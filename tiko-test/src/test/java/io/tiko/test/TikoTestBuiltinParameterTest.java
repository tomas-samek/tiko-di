package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.fixtures.SimpleService;
import java.nio.file.Path;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code @TikoTest} method must be able to mix JUnit's built-in parameters
 * ({@link TempDir}, {@link TestInfo}, {@link TestReporter}, {@link RepetitionInfo}) with
 * container-resolved parameters (#351). The extension previously claimed every parameter,
 * so any of these triggered "competing ParameterResolvers" before the test body ran.
 */
@TikoTest
class TikoTestBuiltinParameterTest {

    @Test
    void tempDirAndTestInfoCoexistWithContainerResolvedParam(
            @TempDir Path tempDir, TestInfo testInfo, SimpleService service) {
        assertThat(tempDir).isNotNull();
        assertThat(testInfo.getTestMethod()).isPresent();
        assertThat(service.hello()).isEqualTo("world");
    }

    @Test
    void testReporterCoexistsWithContainer(TestReporter reporter, Container container) {
        assertThat(reporter).isNotNull();
        assertThat(container).isNotNull();
        assertThat(container.get(SimpleService.class).hello()).isEqualTo("world");
    }

    @RepeatedTest(2)
    void repetitionInfoCoexistsWithContainerResolvedParam(RepetitionInfo repetitionInfo, SimpleService service) {
        assertThat(repetitionInfo.getCurrentRepetition()).isBetween(1, 2);
        assertThat(service).isNotNull();
    }
}
