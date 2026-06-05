package io.tiko.processor.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Walks the abstract-method surface of an interface, including methods inherited from
 * super-interfaces, deduplicated by erased signature. Shared by the proxy generator
 * (uses the methods to synthesise delegating overrides) and the topology writer (uses
 * the names to expose proxy detail for the {@code explain_proxy} MCP tool, see #146).
 *
 * <p>{@code default} and {@code static} interface methods are skipped — they don't
 * need a proxy override.
 */
public final class AbstractInterfaceMethods {

    private AbstractInterfaceMethods() {}

    /**
     * Returns the abstract method elements of the given interface, walking inherited
     * interfaces, deduplicated by erased signature so that an interface re-declaring a
     * method from a parent doesn't surface twice.
     */
    public static List<ExecutableElement> collect(TypeElement interfaceElement, Types typeUtils) {
        LinkedHashMap<String, ExecutableElement> bySig = new LinkedHashMap<>();
        collectInto(interfaceElement, typeUtils, bySig);
        return new ArrayList<>(bySig.values());
    }

    private static void collectInto(
            TypeElement interfaceElement, Types typeUtils, LinkedHashMap<String, ExecutableElement> sink) {
        for (Element enclosed : interfaceElement.getEnclosedElements()) {
            if (enclosed instanceof ExecutableElement method) {
                if (method.getModifiers().contains(Modifier.STATIC)
                        || method.getModifiers().contains(Modifier.DEFAULT)) {
                    continue;
                }
                sink.putIfAbsent(signatureKey(method, typeUtils), method);
            }
        }
        for (TypeMirror superInterface : interfaceElement.getInterfaces()) {
            if (superInterface.getKind() != TypeKind.DECLARED) continue;
            var superElement = ((DeclaredType) superInterface).asElement();
            if (superElement instanceof TypeElement parent) {
                collectInto(parent, typeUtils, sink);
            }
        }
    }

    private static String signatureKey(ExecutableElement method, Types typeUtils) {
        var sb = new StringBuilder(method.getSimpleName().toString()).append('(');
        boolean first = true;
        for (var param : method.getParameters()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(typeUtils.erasure(param.asType()).toString());
        }
        sb.append(')');
        return sb.toString();
    }
}
