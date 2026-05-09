package io.tiko.processor.model;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Represents an @EventHandler method.
 */
public final class EventHandlerModel {

    private final ExecutableElement methodElement;
    private final TypeElement declaringClass;
    private final String methodName;
    private final TypeMirror eventType;
    private final String eventTypeName;
    private final boolean async;
    private final boolean hasEventWrapper; // Second parameter is Event<?> wrapper
    private final List<EventTriggerModel> eventTriggers;

    private EventHandlerModel(Builder builder) {
        this.methodElement = builder.methodElement;
        this.declaringClass = builder.declaringClass;
        this.methodName = builder.methodName;
        this.eventType = builder.eventType;
        this.eventTypeName = builder.eventTypeName;
        this.async = builder.async;
        this.hasEventWrapper = builder.hasEventWrapper;
        this.eventTriggers = List.copyOf(builder.eventTriggers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public ExecutableElement getMethodElement() {
        return methodElement;
    }

    public TypeElement getDeclaringClass() {
        return declaringClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public TypeMirror getEventType() {
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

    public List<EventTriggerModel> getEventTriggers() {
        return eventTriggers;
    }

    /**
     * Returns the component key that declares this handler.
     */
    public String getDeclaringComponentKey() {
        return declaringClass.getQualifiedName().toString();
    }

    public static final class Builder {
        private ExecutableElement methodElement;
        private TypeElement declaringClass;
        private String methodName;
        private TypeMirror eventType;
        private String eventTypeName;
        private boolean async = false;
        private boolean hasEventWrapper = false;
        private List<EventTriggerModel> eventTriggers = new ArrayList<>();

        private Builder() {}

        public Builder methodElement(ExecutableElement methodElement) {
            this.methodElement = methodElement;
            return this;
        }

        public Builder declaringClass(TypeElement declaringClass) {
            this.declaringClass = declaringClass;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder eventType(TypeMirror eventType) {
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

        public Builder eventTriggers(List<EventTriggerModel> eventTriggers) {
            this.eventTriggers = new ArrayList<>(eventTriggers);
            return this;
        }

        public Builder addEventTrigger(EventTriggerModel eventTrigger) {
            this.eventTriggers.add(eventTrigger);
            return this;
        }

        public EventHandlerModel build() {
            if (methodElement == null || declaringClass == null || eventType == null) {
                throw new IllegalStateException("MethodElement, declaringClass, and eventType are required");
            }
            return new EventHandlerModel(this);
        }
    }
}
