package io.tiko.processor.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java records whose canonical constructor sits among other constructors, shared by the javac and
 * KSP model tests (#583). javac reads each header directly; KSP shows a Java record only as its
 * accessors and constructors, so the KSP model reads the components off the canonical constructor.
 * Where a declared canonical constructor can't be told apart from another constructor taking only
 * accessor-matching parameters, KSP reports the components unknown instead of guessing: those
 * records diverge from javac on purpose.
 */
public final class RecordShapes {

    /** One source file, package {@code r}. */
    public static final String SOURCE = """
            package r;

            record Plain(int a, String b) {}

            record SyntheticBesideSubset(int a, String b) {
                SyntheticBesideSubset(int a) { this(a, ""); }
            }

            record SyntheticBesidePermutation(int a, String b) {
                SyntheticBesidePermutation(String b, int a) { this(a, b); }
            }

            record SyntheticBesideWider(int a, String b) {
                public int sum() { return a; }
                SyntheticBesideWider(int a, String b, int sum) { this(a + sum, b); }
            }

            record ExplicitAccessors(int a, String b) {
                int sum() { return a; }
                public String b() { return b; }
                ExplicitAccessors(int a, String b, int extra) { this(a + extra, b); }
            }

            record CompactOnly(int a, String b) {
                CompactOnly {}
            }

            record FullOnly(int a, String b) {
                FullOnly(int a, String b) { this.a = a; this.b = b; }
            }

            record DeclaredBesideNonComponentConstructor(int a, String b) {
                DeclaredBesideNonComponentConstructor(int a, String b) { this.a = a; this.b = b; }
                DeclaredBesideNonComponentConstructor(long text) { this((int) text, ""); }
            }

            record FullBesidePermutation(int a, String b) {
                FullBesidePermutation(String b, int a) { this(a, b); }
                FullBesidePermutation(int a, String b) { this.a = a; this.b = b; }
            }

            record CompactBesidePermutation(int a, String b) {
                CompactBesidePermutation {}
                CompactBesidePermutation(String b, int a) { this(a, b); }
            }

            record DeclaredBesideWider(int a, String b) {
                DeclaredBesideWider(int a, String b) { this.a = a; this.b = b; }
                public int sum() { return a; }
                DeclaredBesideWider(int a, String b, int sum) { this(a + sum, b); }
            }

            record DeclaredBesideSubset(int a, String b) {
                DeclaredBesideSubset(int a) { this(a, ""); }
                DeclaredBesideSubset(int a, String b) { this.a = a; this.b = b; }
            }
            """;

    /** Every record's header components as {@code name:type}, in order: what javac reads. */
    public static final Map<String, List<String>> HEADERS = headers();

    /** The records whose components the KSP model reports unknown (javac still reads them). */
    public static final Set<String> UNKNOWN_ON_KSP = Set.of(
            "r.FullBesidePermutation", "r.CompactBesidePermutation", "r.DeclaredBesideWider", "r.DeclaredBesideSubset");

    private RecordShapes() {}

    private static Map<String, List<String>> headers() {
        var components = List.of("a:int", "b:java.lang.String");
        var headers = new LinkedHashMap<String, List<String>>();
        for (var name : List.of(
                "Plain",
                "SyntheticBesideSubset",
                "SyntheticBesidePermutation",
                "SyntheticBesideWider",
                "ExplicitAccessors",
                "CompactOnly",
                "FullOnly",
                "DeclaredBesideNonComponentConstructor",
                "FullBesidePermutation",
                "CompactBesidePermutation",
                "DeclaredBesideWider",
                "DeclaredBesideSubset")) {
            headers.put("r." + name, components);
        }
        return Collections.unmodifiableMap(headers);
    }
}
