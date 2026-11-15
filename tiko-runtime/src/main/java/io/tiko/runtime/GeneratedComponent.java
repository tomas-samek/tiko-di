package io.tiko.runtime;

import io.tiko.Scope;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Written by the annotation processor on each component factory it generates. It records what the
 * source-retained {@code @Component} said about the component, so a later compile that sees only the
 * compiled module can still read it: a test compile checks a {@code @TestComponent} against the scope
 * of the main component it shadows (#707).
 *
 * <p>Not for application code. {@code CLASS} retention keeps it in the class file for the processor
 * and out of reflection; nothing reads it at run time.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GeneratedComponent {

    /** The scope the component was declared with. */
    Scope scope();
}
