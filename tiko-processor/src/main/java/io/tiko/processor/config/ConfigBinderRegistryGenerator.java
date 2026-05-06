package io.tiko.processor.config;

import com.palantir.javapoet.*;
import io.tiko.config.ConfigBinder;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;
import java.util.StringJoiner;

/**
 * Generates {@code io.tiko.generated.config.ConfigBinderRegistry_<hash>} per module.
 * Hash matches the container's hash so multiple modules don't collide on FQN.
 */
public final class ConfigBinderRegistryGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated.config";

    private final Filer filer;
    private final String hashSuffix;   // e.g. "ab12cd34" — same as TikoContainerImpl_<hash>

    public ConfigBinderRegistryGenerator(Filer filer, String hashSuffix) {
        this.filer = filer;
        this.hashSuffix = hashSuffix;
    }

    public String registryClassFqn() {
        return GENERATED_PACKAGE + ".ConfigBinderRegistry_" + hashSuffix;
    }

    public void generate(List<ConfigurationModel> configs) throws IOException {
        if (configs.isEmpty()) return;

        String className = "ConfigBinderRegistry_" + hashSuffix;

        StringJoiner instances = new StringJoiner(", ");
        for (ConfigurationModel cfg : configs) {
            instances.add("new " + cfg.binderSimpleName() + "()");
        }

        TypeName listOfBinders = ParameterizedTypeName.get(
            ClassName.get(List.class),
            ParameterizedTypeName.get(ClassName.get(ConfigBinder.class), WildcardTypeName.subtypeOf(Object.class)));

        MethodSpec all = MethodSpec.methodBuilder("all")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfBinders)
            .addStatement("return $T.of($L)", List.class, instances.toString())
            .build();

        TypeSpec registry = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(all)
            .build();

        JavaFile.builder(GENERATED_PACKAGE, registry).build().writeTo(filer);
    }
}
