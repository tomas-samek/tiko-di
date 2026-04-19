package io.tiko.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for multiple {@link EventTrigger} annotations.
 *
 * <p>This annotation is automatically used when multiple {@code @EventTrigger}
 * annotations are present on a single method. You typically don't use this
 * annotation directly.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(eventName = "Event1")
 * @EventTrigger(eventName = "Event2")
 * @EventTrigger(eventName = "Event3")
 * public Result handleEvent(SomeEvent event) {
 *     // All three events triggered with same payload
 *     return processEvent(event);
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventTriggers {

    /**
     * Array of event triggers to be executed after the handler completes.
     *
     * @return array of {@link EventTrigger} annotations
     */
    EventTrigger[] value();
}
