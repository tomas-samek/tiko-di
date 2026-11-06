package io.tiko.processor.model;

import io.tiko.annotations.BackoffStrategy;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an @EventHandler method.
 */
public final class EventHandlerModel {

    private final MethodDecl method;
    private final ClassDecl owner;
    private final String methodName;
    private final TypeRef eventType;
    private final String eventTypeName;
    private final boolean async;
    private final boolean hasEventWrapper; // Second parameter is Event<?> wrapper
    private final long timeoutNanos; // 0 = no timeout (#107); async-only, validated at build time
    private final int retries; // 0 = no retries (#108); async-only, validated at build time
    private final long backoffNanos; // base retry delay; 0 = immediate
    private final BackoffStrategy backoffStrategy;
    private final List<EventTriggerModel> eventTriggers;

    private EventHandlerModel(Builder builder) {
        this.method = builder.method;
        this.owner = builder.owner;
        this.methodName = builder.methodName;
        this.eventType = builder.eventType;
        this.eventTypeName = builder.eventTypeName;
        this.async = builder.async;
        this.hasEventWrapper = builder.hasEventWrapper;
        this.timeoutNanos = builder.timeoutNanos;
        this.retries = builder.retries;
        this.backoffNanos = builder.backoffNanos;
        this.backoffStrategy = builder.backoffStrategy;
        this.eventTriggers = List.copyOf(builder.eventTriggers);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The handler method; generated dispatch calls it by {@link MethodDecl#jvmName()}. */
    public MethodDecl getMethod() {
        return method;
    }

    /** The class declaring the handler. */
    public ClassDecl getOwner() {
        return owner;
    }

    public String getMethodName() {
        return methodName;
    }

    public TypeRef getEventType() {
        return eventType;
    }

    public String getEventTypeName() {
        return eventTypeName;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean hasEventWrapper() {
        return hasEventWrapper;
    }

    /** Execution-timeout budget in nanoseconds, or {@code 0} for no timeout (#107). Always async-only. */
    public long getTimeoutNanos() {
        return timeoutNanos;
    }

    /** True when this handler declared a positive {@code @EventHandler(timeout = ...)}. */
    public boolean hasTimeout() {
        return timeoutNanos > 0;
    }

    /** Retry count after the initial attempt, or {@code 0} for none (#108). Always async-only. */
    public int getRetries() {
        return retries;
    }

    /** Base retry backoff in nanoseconds, or {@code 0} for immediate retries. */
    public long getBackoffNanos() {
        return backoffNanos;
    }

    /** How the backoff grows across retries. */
    public BackoffStrategy getBackoffStrategy() {
        return backoffStrategy;
    }

    /** True when this handler declared {@code @EventHandler(retries > 0)}. */
    public boolean hasRetries() {
        return retries > 0;
    }

    public List<EventTriggerModel> getEventTriggers() {
        return eventTriggers;
    }

    /**
     * Returns the component key that declares this handler.
     */
    public String getDeclaringComponentKey() {
        return owner.qualifiedName();
    }

    public static final class Builder {
        private MethodDecl method;
        private ClassDecl owner;
        private String methodName;
        private TypeRef eventType;
        private String eventTypeName;
        private boolean async = false;
        private boolean hasEventWrapper = false;
        private long timeoutNanos = 0L;
        private int retries = 0;
        private long backoffNanos = 0L;
        private BackoffStrategy backoffStrategy = BackoffStrategy.FIXED;
        private List<EventTriggerModel> eventTriggers = new ArrayList<>();

        private Builder() {}

        public Builder method(MethodDecl method) {
            this.method = method;
            return this;
        }

        public Builder owner(ClassDecl owner) {
            this.owner = owner;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder eventType(TypeRef eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder eventTypeName(String eventTypeName) {
            this.eventTypeName = eventTypeName;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        public Builder hasEventWrapper(boolean hasEventWrapper) {
            this.hasEventWrapper = hasEventWrapper;
            return this;
        }

        public Builder timeoutNanos(long timeoutNanos) {
            this.timeoutNanos = timeoutNanos;
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        public Builder backoffNanos(long backoffNanos) {
            this.backoffNanos = backoffNanos;
            return this;
        }

        public Builder backoffStrategy(BackoffStrategy backoffStrategy) {
            this.backoffStrategy = backoffStrategy;
            return this;
        }

        public Builder eventTriggers(List<EventTriggerModel> eventTriggers) {
            this.eventTriggers = new ArrayList<>(eventTriggers);
            return this;
        }

        public Builder addEventTrigger(EventTriggerModel eventTrigger) {
            this.eventTriggers.add(eventTrigger);
            return this;
        }

        public EventHandlerModel build() {
            if (method == null || owner == null || eventType == null) {
                throw new IllegalStateException("method, owner, and eventType are required");
            }
            return new EventHandlerModel(this);
        }
    }
}
