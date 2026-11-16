package io.tiko.processor;

import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * #731: resolving the handler's component is part of delivering the event. The generated dispatch
 * does it where a failure is caught and routed to the {@code ErrorHandler} as that handler's
 * {@code EventHandlerError}, for a sync handler and for an async handler resolved on the
 * publishing thread (a lifecycle-event handler).
 */
class UndeliverableEventGenerationTest {

    @Test
    void handlerLookupIsRoutedToTheErrorHandlerWhenItFails() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines(
                "demo.Listener",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.events.ApplicationStartedEvent;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Listener {",
                "  @EventHandler public void onText(String text) {}",
                "  @EventHandler(async = true) public void onStarted(ApplicationStartedEvent started) {}",
                "}"));
        CompilationSubject.assertThat(c).succeeded();

        assertThat(generated(c, "EventRegistry"))
                .as("sync: the lookup sits inside the try whose catch routes to the ErrorHandler")
                .containsSubsequence(
                        "try {",
                        "Listener __handler = container.getListener();",
                        "__handler.onText(event);",
                        "} catch (Exception __t) {",
                        "onError(new EventHandlerError(HANDLER_INFO_")
                .as("async on the publishing thread: a failed lookup is routed and the dispatch ends")
                .containsSubsequence(
                        "final Listener __handler;",
                        "__handler = container.getListener();",
                        "} catch (Exception __t) {",
                        "__err.onError(new EventHandlerError(HANDLER_INFO_",
                        "return;");
    }
}
