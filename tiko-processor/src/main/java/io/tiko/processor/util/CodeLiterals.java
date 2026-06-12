package io.tiko.processor.util;

/**
 * Escaping for values spliced into generated call-expression strings. Most generated
 * code goes through JavaPoet's {@code $S}, which escapes automatically — but the
 * dependency-resolution helpers build call expressions as plain strings (later embedded
 * via {@code $L}), so qualifier values inside them must be escaped by hand or a quote
 * in a {@code @Component(name = ...)} corrupts the generated source (#333).
 */
public final class CodeLiterals {

    private CodeLiterals() {}

    /** Escapes {@code s} for embedding inside a double-quoted Java string literal. */
    public static String javaString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }
}
