package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as a configuration root. The processor generates a binder
 * for this record at compile time; at runtime the container loads YAML
 * matching {@link #prefix()} and binds it to a record instance, registered
 * as a SINGLETON-scoped bean.
 *
 * <p>Only {@code record} types may be annotated. Nested records inside a
 * {@code @Configuration} record are bound automatically and do not need this
 * annotation themselves — {@code @Configuration} marks the top-level entry
 * point that owns a YAML root prefix.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Configuration {
    /**
     * Top-level YAML key under which this record's data is read.
     *
     * @return the YAML prefix
     */
    String prefix();
}
