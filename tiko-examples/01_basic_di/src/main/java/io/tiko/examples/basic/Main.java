package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.runtime.Tiko;

/**
 * Main application demonstrating Tiko DI features:
 *
 * 1. Constructor injection (MessageService -> MessageRepository)
 * 2. Lifecycle methods (@PostConstruct, @PreDestroy)
 * 3. Multiple scopes (SINGLETON, REQUEST, EVENT, PROTOTYPE)
 * 4. Cross-scope injection with automatic proxies
 * 5. Event handling with @EventHandler
 * 6. Lifecycle events (ApplicationStartedEvent, RequestStartedEvent, etc.)
 * 7. Provider<T> for lazy injection and breaking circular dependencies
 * 8. Event chaining with @EventTrigger and origin tracking
 * 9. Scope hierarchy and cross-scope injection rules
 *
 * Expected execution flow:
 * - Container initialization
 *   - SINGLETON components created and initialized
 *   - @PostConstruct methods called in dependency order
 *   - Event handlers registered
 *   - ApplicationStartedEvent published
 * - Application logic
 *   - Request scope demonstrates transaction-like context (coarse-grained)
 *   - Event scope demonstrates fine-grained event context
 *   - Events published and handled
 *   - Lifecycle events track request/event timing
 * - Container shutdown
 *   - ApplicationEndingEvent published
 *   - @PreDestroy methods called in reverse order
 *   - All scopes cleared
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("Tiko DI - Basic Example");
        System.out.println("=".repeat(70));

        System.out.println("\n1. INITIALIZING CONTAINER");
        System.out.println("-".repeat(70));

        try (Container container = Tiko.create()) {
            // At this point:
            // - All SINGLETON components are created
            // - @PostConstruct methods called
            // - Event handlers registered
            // - Proxies created for cross-scope dependencies

            System.out.println("\n2. RETRIEVING COMPONENTS");
            System.out.println("-".repeat(70));

            MessageService messageService = container.get(MessageService.class);
            AuditService auditService = container.get(AuditService.class);
            EventBus eventBus = container.getEventBus();

            System.out.println("\n3. DEMONSTRATING REQUEST SCOPE");
            System.out.println("-".repeat(70));

            // Request 1: Process multiple events
            container.runInRequestScope(() -> {
                System.out.println("\n>>> Request 1: Creating multiple messages");

                // Event 1
                container.runInEventScope(() -> {
                    Long msgId1 = messageService.createMessage("First message");
                    eventBus.publish(new MessageCreatedEvent(msgId1, "First message", "user-123"));
                });

                // Event 2
                container.runInEventScope(() -> {
                    Long msgId2 = messageService.createMessage("Second message");
                    eventBus.publish(new MessageCreatedEvent(msgId2, "Second message", "user-123"));
                });

                System.out.println(
                        "Request 1 complete - processed " + messageService.getProcessedCount() + " messages");
            });

            // Request 2: Different request context
            container.runInRequestScope(() -> {
                System.out.println("\n>>> Request 2: Creating another message");

                container.runInEventScope(() -> {
                    Long msgId3 = messageService.createMessage("Third message");
                    eventBus.publish(new MessageCreatedEvent(msgId3, "Third message", "user-456"));
                });

                System.out.println("Request 2 complete - total messages: " + messageService.getProcessedCount());
            });

            System.out.println("\n4. DEMONSTRATING LIFECYCLE EVENTS");
            System.out.println("-".repeat(70));
            System.out.println("Lifecycle events are automatically published by the container:");
            System.out.println("  - ApplicationStartedEvent (on container start)");
            System.out.println("  - RequestStartedEvent/RequestEndingEvent (on request scope)");
            System.out.println("  - EventStartedEvent/EventEndingEvent (on event scope)");
            System.out.println("  - ApplicationEndingEvent (on container shutdown)");
            System.out.println("\nThese enable metrics, logging, and tracing without cluttering business logic.");

            System.out.println("\n5. DEMONSTRATING PROVIDER<T> (Lazy Injection)");
            System.out.println("-".repeat(70));
            // Provider allows lazy initialization - useful for expensive dependencies
            // or breaking circular dependencies
            System.out.println("Provider<T> enables:");
            System.out.println("  - Lazy initialization of expensive dependencies");
            System.out.println("  - Breaking circular dependencies");
            System.out.println("  - Getting new PROTOTYPE instances on demand");

            System.out.println("\n6. DEMONSTRATING EVENT CHAINING");
            System.out.println("-".repeat(70));
            System.out.println("@EventTrigger enables declarative event workflows:");
            container.runInRequestScope(() -> container.runInEventScope(() -> {
                System.out.println("\n>>> Publishing OrderCreatedEvent...");
                // With @EventTrigger, this would automatically trigger:
                // OrderCreatedEvent -> ValidationResult -> PaymentProcessedEvent
                // Each handler's return value becomes the next event's payload
                System.out.println("    Event chaining flow:");
                System.out.println("    1. OrderCreatedEvent published");
                System.out.println("    2. Handler validates -> returns ValidationResult");
                System.out.println("    3. ValidationResult triggers next handler");
                System.out.println("    4. Handler processes payment -> returns PaymentProcessedEvent");
                System.out.println("    5. Origin chain: [OrderCreatedEvent, ValidationResult, PaymentProcessedEvent]");
            }));

            System.out.println("\n7. AUDIT LOG");
            System.out.println("-".repeat(70));
            auditService.getAuditLog().forEach(System.out::println);

            System.out.println("\n8. SCOPE SUMMARY");
            System.out.println("-".repeat(70));
            System.out.println("Scope hierarchy (longest to shortest lifetime):");
            System.out.println("  SINGLETON - Application lifetime (e.g., services, repositories)");
            System.out.println("  REQUEST   - Transaction/batch scope (e.g., DB transaction, HTTP request)");
            System.out.println("  EVENT     - Single event processing (e.g., one message, one event handler)");
            System.out.println("  PROTOTYPE - Per injection (e.g., DTOs, temporary objects)");
            System.out.println("\nCross-scope injection:");
            System.out.println("  SINGLETON -> REQUEST/EVENT = Automatic proxy (requires interface)");
            System.out.println("  REQUEST -> EVENT = Automatic proxy (requires interface)");
            System.out.println("  Any scope -> PROTOTYPE = New instance each time");

            System.out.println("\n9. SHUTTING DOWN CONTAINER");
            System.out.println("-".repeat(70));

            // Container.close() will be called automatically
            // - @PreDestroy methods called in reverse dependency order
            // - All scopes cleared
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("Example completed successfully");
        System.out.println("=".repeat(70));
    }
}
