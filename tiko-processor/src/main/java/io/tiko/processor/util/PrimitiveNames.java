package io.tiko.processor.util;

/** How error messages name a JVM primitive to a Kotlin author (#748). */
public final class PrimitiveNames {

    private PrimitiveNames() {}

    /** The Kotlin type that compiles to the JVM primitive: {@code int} is {@code Int}. */
    public static String kotlin(String primitive) {
        return Character.toUpperCase(primitive.charAt(0)) + primitive.substring(1);
    }

    /**
     * The fix a Kotlin author can write: a small class that holds the value, named after the member
     * that carries it ({@code port} gives {@code class Port(val value: Int)}).
     */
    public static String holderFix(String kotlinType, String memberName) {
        String holder = Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        return "Wrap the value in a small class, e.g. class " + holder + "(val value: " + kotlinType + ")";
    }
}
