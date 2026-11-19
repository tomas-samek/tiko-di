package io.tiko.examples.quickstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.javalin.util.JavalinBindException;
import io.tiko.runtime.Tiko;
import java.net.BindException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The server binds its port while the container starts, so a taken port fails boot. What the
 * container had started is shut down, and no thread is left to keep the JVM running.
 */
class TakenPortIT {

    @Test
    void aTakenPortFailsBootAndLeavesNoThread() throws Exception {
        try (var taken = new ServerSocket(0)) {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            List<Thread> outlivingClose = new CopyOnWriteArrayList<>();

            assertThatThrownBy(() -> Tiko.create(QuickstartIT.onPort(taken.getLocalPort())
                            .onThreadsOutlivingClose(outlivingClose::addAll)
                            .build()))
                    .isInstanceOf(JavalinBindException.class)
                    .hasMessageContaining("Port already in use")
                    .hasRootCauseInstanceOf(BindException.class);

            assertThat(outlivingClose).as("threads the container started").isEmpty();
            // A closed pool's threads end shortly after close() returns. Javalin 7.2.3 starts a thread
            // with every instance that sleeps 5 s, then warns when the server never started, and ends.
            await().pollInSameThread()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(Thread.getAllStackTraces().keySet())
                            .as("threads started by the failed boot, Javalin's and Jetty's included")
                            .filteredOn(thread -> !before.contains(thread) && thread.isAlive())
                            .extracting(Thread::getName)
                            .isEmpty());
        }
    }
}
