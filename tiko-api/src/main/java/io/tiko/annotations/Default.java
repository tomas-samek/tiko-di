package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Supplies a default value for a {@code @Configuration} record component
 * when the corresponding YAML key is absent. The string is parsed at
 * compile time using the same coercer the runtime uses for that field's
 * declared type, so a malformed default fails the build.
 *
 * <p>Cannot be combined with {@code Optional<X>} — {@code Optional} already
 * encodes "may be absent" and the two would conflict.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Default {
    /**
     * The default value as a string, parsed at compile time according to the field's type.
     *
     * @return the default value
     */
    String value();
}
