// tiko-processor/src/main/java/io/tiko/processor/config/ConfigFieldModel.java
package io.tiko.processor.config;

import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.TypeRef;

/**
 * One record component on a {@code @Configuration} record.
 */
public record ConfigFieldModel(
        FieldDecl component, // the record component
        String fieldName, // record component name (also default YAML key)
        String yamlKey, // either fieldName or @Key("…") override
        TypeRef type, // raw declared type (e.g., Optional<Duration>)
        Cardinality cardinality, // REQUIRED / OPTIONAL / DEFAULTED
        String defaultValue // raw @Default("…") string; null unless DEFAULTED
        ) {
    public enum Cardinality {
        REQUIRED,
        OPTIONAL,
        DEFAULTED
    }
}
