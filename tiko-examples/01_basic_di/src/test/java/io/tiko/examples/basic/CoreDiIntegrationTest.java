package io.tiko.examples.basic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.NoSuchComponentException;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

class CoreDiIntegrationTest {

    @Test
    void get_returnsSameSingletonInstance() {
        try (Container container = Tiko.create()) {
            MessageService a = container.get(MessageService.class);
            MessageService b = container.get(MessageService.class);
            assertThat(a).isSameAs(b);
        }
    }

    @Test
    void get_injectsConstructorDependencies() {
        try (Container container = Tiko.create()) {
            MessageService service = container.get(MessageService.class);
            Long id = service.createMessage("hello");
            assertThat(service.getMessage(id)).contains("hello");
        }
    }

    @Test
    void get_unknownType_throws() {
        try (Container container = Tiko.create()) {
            assertThatThrownBy(() -> container.get(String.class))
                    .isInstanceOf(NoSuchComponentException.class)
                    // structured field carries the requested type, not just a message
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(NoSuchComponentException.class))
                    .satisfies(e -> {
                        assertThat(e.type()).isEqualTo(String.class);
                        assertThat(e.qualifier()).isNull();
                    });
        }
    }

    @Test
    void postConstruct_runsAfterInjection() {
        // MessageRepository.@PostConstruct pre-populates 2 messages
        try (Container container = Tiko.create()) {
            MessageRepository repo = container.get(MessageRepository.class);
            assertThat(repo.count()).isEqualTo(2);
        }
    }

    @Test
    void preDestroy_runsOnShutdown() {
        MessageRepository repo;
        try (Container container = Tiko.create()) {
            repo = container.get(MessageRepository.class);
            assertThat(repo.count()).isGreaterThan(0);
        }
        // MessageRepository.cleanup() clears the map on @PreDestroy
        assertThat(repo.count()).isZero();
    }

    @Test
    void requestScope_isolatesInstancesAcrossScopes() {
        try (Container container = Tiko.create()) {
            AuditService audit = container.get(AuditService.class);

            String first = captureLastAuditEntry(container, audit, 1L, "a", "u1");
            String second = captureLastAuditEntry(container, audit, 2L, "b", "u2");

            assertThat(requestIdOf(first)).isNotEqualTo(requestIdOf(second));
            assertThat(eventIdOf(first)).isNotEqualTo(eventIdOf(second));
        }
    }

    @Test
    void eventBus_invokesRegisteredHandler() {
        try (Container container = Tiko.create()) {
            AuditService audit = container.get(AuditService.class);
            int before = audit.getAuditCount();

            container.runInEventScope(
                    () -> container.getEventBus().publish(new MessageCreatedEvent(99L, "content", "tester")));

            assertThat(audit.getAuditCount()).isEqualTo(before + 1);
        }
    }

    @Test
    void crossScopeProxy_resolvesPerScopeWithinSingleton() {
        // AuditService is SINGLETON but injected with REQUEST/EVENT contexts via proxies.
        // The proxy must resolve to the current scope's instance on each call.
        try (Container container = Tiko.create()) {
            AuditService audit = container.get(AuditService.class);

            String entryA = captureLastAuditEntry(container, audit, 10L, "x", "u");
            String entryB = captureLastAuditEntry(container, audit, 11L, "y", "u");

            // Same AuditService singleton observes different request/event ids
            // only if the proxy delegates to the current scope.
            assertThat(requestIdOf(entryA)).isNotEqualTo(requestIdOf(entryB));
        }
    }

    private static String captureLastAuditEntry(
            Container container, AuditService audit, long msgId, String content, String user) {
        return container.supplyInEventScope(() -> {
            container.getEventBus().publish(new MessageCreatedEvent(msgId, content, user));
            return audit.getAuditLog().get(audit.getAuditLog().size() - 1);
        });
    }

    private static String requestIdOf(String auditEntry) {
        return between(auditEntry, "Request=", ",");
    }

    private static String eventIdOf(String auditEntry) {
        return between(auditEntry, "Event=", ",");
    }

    private static String between(String s, String prefix, String suffix) {
        int start = s.indexOf(prefix) + prefix.length();
        int end = s.indexOf(suffix, start);
        return s.substring(start, end);
    }
}
