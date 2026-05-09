package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * SINGLETON service demonstrating:
 * 1. Cross-scope injection (REQUEST-scoped context into SINGLETON)
 * 2. Event handling with @EventHandler
 */
@Component(scope = Scope.SINGLETON)
public class AuditService {

    private final RequestContext requestContext; // Proxy will be injected
    private final EventContext eventContext; // Proxy will be injected
    private final List<String> auditLog = new ArrayList<>();

    @Inject
    public AuditService(RequestContext requestContext, EventContext eventContext) {
        System.out.println("[AuditService] Constructor called");
        System.out.println("[AuditService] RequestContext type: "
                + requestContext.getClass().getName());
        System.out.println(
                "[AuditService] EventContext type: " + eventContext.getClass().getName());
        this.requestContext = requestContext;
        this.eventContext = eventContext;
    }

    @PostConstruct
    public void init() {
        System.out.println("[AuditService] @PostConstruct - Audit service ready");
    }

    /**
     * Event handler for MessageCreatedEvent.
     * Demonstrates declarative event handling.
     */
    @EventHandler
    public void onMessageCreated(MessageCreatedEvent event) {
        String logEntry = String.format(
                "[AUDIT] Request=%s, Event=%s, User=%s created message %d: %s",
                requestContext.getRequestId(),
                eventContext.getEventId(),
                event.userId(),
                event.messageId(),
                event.content());

        System.out.println(logEntry);
        auditLog.add(logEntry);
    }

    public List<String> getAuditLog() {
        return new ArrayList<>(auditLog);
    }

    public int getAuditCount() {
        return auditLog.size();
    }
}
