// tiko-processor/src/main/java/io/tiko/processor/config/ConfigFieldModel.java
package io.tiko.processor.config;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * One record component on a {@code @Configuration} record.
 */
public record ConfigFieldModel(
        VariableElement element,
        String fieldName, // record component name (also default YAML key)
        String yamlKey, // either fieldName or @Key("…") override
        TypeMirror type, // raw declared type (e.g., Optional<Duration>)
        Cardinality cardinality, // REQUIRED / OPTIONAL / DEFAULTED
        String defaultValue // raw @Default("…") string; null unless DEFAULTED
        ) {
    public enum Cardinality {
        REQUIRED,
        OPTIONAL,
        DEFAULTED
    }
}
