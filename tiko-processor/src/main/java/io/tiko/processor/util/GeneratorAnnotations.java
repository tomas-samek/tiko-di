package io.tiko.processor.util;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;

/**
 * Helpers for annotating generated types. Centralizing this keeps every generator's output
 * consistently marked with {@code @javax.annotation.processing.Generated} so IDEs and coverage
 * tools can recognize generated sources.
 */
public final class GeneratorAnnotations {

    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    private GeneratorAnnotations() {}

    /**
     * Builds a {@code @Generated} annotation whose {@code value} is the fully qualified name of
     * the generator class. The annotation should be added to every top-level {@code TypeSpec}
     * the processor emits.
     */
    public static AnnotationSpec generatedBy(Class<?> generatorClass) {
        return AnnotationSpec.builder(GENERATED)
                .addMember("value", "$S", generatorClass.getName())
                .build();
    }
}
