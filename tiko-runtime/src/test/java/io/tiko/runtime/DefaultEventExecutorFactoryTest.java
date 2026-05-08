package io.tiko.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEventExecutorFactoryTest {

    @Test
    void produces_threadpool_with_documented_settings() {
        ExecutorService es = DefaultEventExecutorFactory.create();
        try {
            assertThat(es).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) es;

            int cores = Runtime.getRuntime().availableProcessors();
            assertThat(tpe.getCorePoolSize()).isEqualTo(Math.max(2, cores / 2));
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(cores * 4);
            assertThat(tpe.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
            assertThat(tpe.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(1024);
            assertThat(tpe.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void threads_are_daemon_and_named_tiko_event_async() throws Exception {
        ExecutorService es = DefaultEventExecutorFactory.create();
        try {
            AtomicReference<Thread> captured = new AtomicReference<>();
            es.submit(() -> captured.set(Thread.currentThread())).get();

            Thread t = captured.get();
            assertThat(t.isDaemon()).isTrue();
            assertThat(t.getName()).startsWith("tiko-event-async-");
        } finally {
            es.shutdownNow();
        }
    }
}
