package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.examples.multimodule.modulea.NotesAsked;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * #811: the unit of work an async handler runs in is open in every module of the application.
 * Module A's {@code AsyncNoteReader} lists {@code UnitNote}s through a picker; Module B adds an
 * EVENT-scoped one.
 */
class MultiModuleAsyncUnitTest {

    private static final String[] NOTES_OF_BOTH_MODULES = {"module-a", "module-b"};

    @Test
    void asyncHandlerReachesTheEventScopedComponentsOfEveryModule() throws Exception {
        var answer = new CompletableFuture<List<String>>();
        ErrorHandler failTheAnswer = error -> answer.completeExceptionally(error.cause());
        try (Container container =
                Tiko.create(TikoOptions.builder().errorHandler(failTheAnswer).build())) {
            container.getEventBus().publish(new NotesAsked(answer));

            assertThat(answer.get(5, TimeUnit.SECONDS)).containsExactlyInAnyOrder(NOTES_OF_BOTH_MODULES);
        }
    }

    @Test
    void asyncHandlerPublishedFromInsideAUnitGetsAUnitOfItsOwnInEveryModule() throws Exception {
        var answer = new CompletableFuture<List<String>>();
        ErrorHandler failTheAnswer = error -> answer.completeExceptionally(error.cause());
        try (Container container =
                Tiko.create(TikoOptions.builder().errorHandler(failTheAnswer).build())) {
            container.runInEventScope(() -> container.getEventBus().publish(new NotesAsked(answer)));

            assertThat(answer.get(5, TimeUnit.SECONDS)).containsExactlyInAnyOrder(NOTES_OF_BOTH_MODULES);
        }
    }
}
