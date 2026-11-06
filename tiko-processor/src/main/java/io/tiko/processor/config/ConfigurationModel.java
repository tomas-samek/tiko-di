// tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationModel.java
package io.tiko.processor.config;

import io.tiko.processor.core.model.ClassDecl;
import java.util.List;

/**
 * One {@code @Configuration} record collected from the source set.
 */
public record ConfigurationModel(
        ClassDecl declaration,
        String packageName,
        String simpleName, // e.g. DbConfig
        String qualifiedName, // e.g. io.example.app.DbConfig
        String prefix, // @Configuration#prefix
        List<ConfigFieldModel> fields) {
    public String binderSimpleName() {
        return simpleName + "Binder";
    }

    public String binderQualifiedName() {
        return "io.tiko.generated.config." + binderSimpleName();
    }
}
