package io.tiko.processor.model;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.type.TypeMirror;

/**
 * Represents an @EventTrigger annotation on an event handler.
 * Defines events to trigger after the handler completes.
 */
public final class EventTriggerModel {

    private final String eventName;
    private final boolean async;
    private final boolean spread;
    private final List<TypeMirror> guardClasses;

    private EventTriggerModel(Builder builder) {
        this.eventName = builder.eventName;
        this.async = builder.async;
        this.spread = builder.spread;
        this.guardClasses = List.copyOf(builder.guardClasses);
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

    /**
     * The guards declared on the annotation, in declaration order. Empty when only the
     * default {@code AlwaysAllow} is declared (callers don't need to instantiate that
     * — codegen treats an empty list as "no guard").
     */
    public List<TypeMirror> getGuardClasses() {
        return guardClasses;
    }

    public boolean hasGuard() {
        return !guardClasses.isEmpty();
    }

    public static final class Builder {
        private String eventName;
        private boolean async = false;
        private boolean spread = false;
        private List<TypeMirror> guardClasses = new ArrayList<>();

        private Builder() {}

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

        public Builder guardClasses(List<TypeMirror> guardClasses) {
            this.guardClasses = new ArrayList<>(guardClasses);
            return this;
        }

        public Builder addGuardClass(TypeMirror guardClass) {
            this.guardClasses.add(guardClass);
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
