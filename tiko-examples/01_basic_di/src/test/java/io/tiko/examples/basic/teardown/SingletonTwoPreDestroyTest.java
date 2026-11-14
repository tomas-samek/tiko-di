package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorContext;
import io.tiko.PreDestroyFailure;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #712: a SINGLETON may declare several {@code @PreDestroy} methods. Shutdown runs each of them, in
 * declaration order, and one that fails is routed without skipping the next, whether the hooks run
 * inline or under a {@code teardownTimeout}.
 */
class SingletonTwoPreDestroyTest {

    private final List<ErrorContext> routed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @AfterEach
    void stopFailing() {
        TwoHookSingleton.failFirstHook = false;
    }

    static Stream<Arguments> teardownModes() {
        return Stream.of(
                Arguments.of("inline", TikoOptions.builder()),
                Arguments.of("bounded", TikoOptions.builder().teardownTimeout(Duration.ofSeconds(5))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("teardownModes")
    void bothHooksRunInDeclarationOrder(String mode, TikoOptions.Builder options) {
        Tiko.create(options.errorHandler(routed::add).build()).shutdown();

        assertThat(hooksRun()).containsExactly("TwoHook.flush", "TwoHook.release");
        assertThat(routed).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("teardownModes")
    void failingHookIsRoutedAndTheNextStillRuns(String mode, TikoOptions.Builder options) {
        TwoHookSingleton.failFirstHook = true;

        Tiko.create(options.errorHandler(routed::add).build()).shutdown();

        assertThat(hooksRun()).containsExactly("TwoHook.flush", "TwoHook.release");
        assertThat(routed)
                .singleElement()
                .isInstanceOfSatisfying(
                        PreDestroyFailure.class,
                        failure -> assertThat(failure.cause()).hasMessage("flush failed"));
    }

    private static List<String> hooksRun() {
        return TeardownRecorder.order.stream()
                .filter(entry -> entry.startsWith("TwoHook."))
                .toList();
    }
}
