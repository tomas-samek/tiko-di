package io.tiko.processor.model;

import javax.lang.model.type.TypeMirror;
import java.util.Optional;

/**
 * Represents an @EventTrigger annotation on an event handler.
 * Defines events to trigger after the handler completes.
 */
public final class EventTriggerModel {

    private final String eventName;
    private final boolean async;
    private final boolean spread;
    private final TypeMirror guardClass;  // EventTriggerGuard implementation

    private EventTriggerModel(Builder builder) {
        this.eventName = builder.eventName;
        this.async = builder.async;
        this.spread = builder.spread;
        this.guardClass = builder.guardClass;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventName() {
        return eventName;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isSpread() {
        return spread;
    }

    public Optional<TypeMirror> getGuardClass() {
        return Optional.ofNullable(guardClass);
    }

    public boolean hasGuard() {
        return guardClass != null;
    }

    public static final class Builder {
        private String eventName;
        private boolean async = false;
        private boolean spread = false;
        private TypeMirror guardClass;

        private Builder() {
        }

        public Builder eventName(String eventName) {
            this.eventName = eventName;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        public Builder spread(boolean spread) {
            this.spread = spread;
            return this;
        }

        public Builder guardClass(TypeMirror guardClass) {
            this.guardClass = guardClass;
            return this;
        }

        public EventTriggerModel build() {
            if (eventName == null || eventName.isEmpty()) {
                throw new IllegalStateException("EventName is required");
            }
            return new EventTriggerModel(this);
        }
    }
}
