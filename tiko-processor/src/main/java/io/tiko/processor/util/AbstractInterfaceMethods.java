package io.tiko.processor.util;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Walks the instance-method surface of an interface, including methods inherited from
 * super-interfaces, deduplicated by erased signature. Shared by the proxy generator
 * (uses the methods to synthesise delegating overrides) and the topology writer (uses
 * the names to expose proxy detail for the {@code explain_proxy} MCP tool, see #146).
 *
 * <p>{@code default} methods are part of it: the component may override one, and a proxy that
 * left it to the interface would run the default body where the component's was meant (#741).
 * {@code static} interface methods are skipped — they are not called on an instance.
 */
public final class AbstractInterfaceMethods {

    private AbstractInterfaceMethods() {}

    /**
     * Returns the instance methods of the given interface, abstract and default, walking inherited
     * interfaces, deduplicated by erased signature so that an interface re-declaring a
     * method from a parent doesn't surface twice.
     */
    public static List<MethodDecl> collect(ClassDecl interfaceDecl) {
        LinkedHashMap<String, MethodDecl> bySig = new LinkedHashMap<>();
        collectInto(interfaceDecl, bySig);
        return new ArrayList<>(bySig.values());
    }

    private static void collectInto(ClassDecl interfaceDecl, LinkedHashMap<String, MethodDecl> sink) {
        for (var method : interfaceDecl.methods()) {
            if (method.isStatic()) {
                continue;
            }
            sink.putIfAbsent(signatureKey(method), method);
        }
        for (var superInterface : interfaceDecl.interfaces()) {
            TypeRefs.declared(superInterface).ifPresent(parent -> collectInto(parent, sink));
        }
    }

    /** {@code name(erased,param,types)}; {@link io.tiko.processor.core.model.TypeRef#erasedName()} is the erasure's name. */
    private static String signatureKey(MethodDecl method) {
        var sb = new StringBuilder(method.name()).append('(');
        boolean first = true;
        for (var param : method.parameters()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(param.type().erasedName());
        }
        sb.append(')');
        return sb.toString();
    }
}
