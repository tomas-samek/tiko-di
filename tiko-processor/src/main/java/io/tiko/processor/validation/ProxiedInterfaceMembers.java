package io.tiko.processor.validation;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import io.tiko.processor.core.model.MethodDecl;
import java.util.HashSet;
import java.util.Optional;
import java.util.stream.Stream;

/** What a generated proxy can and cannot override of the interface it implements. */
final class ProxiedInterfaceMembers {

    private ProxiedInterfaceMembers() {}

    /**
     * The first type parameter in {@code method}'s signature that is not the method's own. A proxied
     * interface is not generic itself, so such a parameter belongs to a generic interface the
     * method is inherited from ({@code T get()} of {@code Supplier<String>}), where only the
     * inheriting interface knows what it stands for (#741). A Kotlin member is overridden by its
     * erasure when a parameter is generic (#704), so only its return type is looked at.
     */
    static Optional<String> inheritedTypeParameter(MethodDecl method) {
        var own = new HashSet<String>();
        method.typeVariables().forEach(variable -> own.add(variable.name()));
        var parameterTypes = method.hasKotlinJvmSignature()
                ? Stream.<TypeName>empty()
                : method.parameters().stream().map(parameter -> parameter.type().javaPoet());
        return Stream.concat(Stream.of(method.returnType().javaPoet()), parameterTypes)
                .flatMap(ProxiedInterfaceMembers::typeVariablesIn)
                .filter(name -> !own.contains(name))
                .findFirst();
    }

    private static Stream<String> typeVariablesIn(TypeName type) {
        return switch (type) {
            case TypeVariableName variable -> Stream.of(variable.name());
            case ArrayTypeName array -> typeVariablesIn(array.componentType());
            case ParameterizedTypeName parameterized ->
                parameterized.typeArguments().stream().flatMap(ProxiedInterfaceMembers::typeVariablesIn);
            case WildcardTypeName wildcard ->
                Stream.concat(wildcard.upperBounds().stream(), wildcard.lowerBounds().stream())
                        .flatMap(ProxiedInterfaceMembers::typeVariablesIn);
            default -> Stream.empty();
        };
    }
}
