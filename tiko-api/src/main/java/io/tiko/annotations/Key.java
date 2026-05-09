package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the YAML key used for a {@code @Configuration} record component.
 * By default the field name is used verbatim (camelCase). Use {@code @Key}
 * when the YAML uses a different naming style (kebab-case, snake_case, etc.).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Key {
    /**
     * The YAML key name for this record component.
     *
     * @return the YAML key
     */
    String value();
}
