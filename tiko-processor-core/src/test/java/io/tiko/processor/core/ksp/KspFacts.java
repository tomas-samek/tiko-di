package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.SymbolProcessorProvider;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.MethodDecl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads named facts off the neutral model in one KSP round, so a parameterized test can assert
 * each fact as its own row without paying for a compilation per row. KSP symbols only live while
 * a round runs, so the reads happen inside it and only their results (or what they threw) are kept.
 */
final class KspFacts {

    /** One fact: a read over the first round's env and the value it must produce. */
    record Fact(String name, Function<ProcessingEnv, Object> read, Object expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** What a read threw, kept as a value so a row can expect it. */
    record Threw(Class<? extends Throwable> type) {}

    private KspFacts() {}

    static Fact fact(String name, Function<ProcessingEnv, Object> read, Object expected) {
        return new Fact(name, read, expected);
    }

    /** Runs every fact's read in the first round over {@code sources}; the compilation may fail. */
    static Map<String, Object> collect(List<Fact> facts, Map<String, String> options, SourceFile... sources) {
        var results = new LinkedHashMap<String, Object>();
        SymbolProcessorProvider probe = environment -> resolver -> {
            if (results.isEmpty()) {
                var env = KspProcessingEnv.of(
                        resolver, environment.getCodeGenerator(), environment.getLogger(), environment.getOptions());
                facts.forEach(fact -> results.put(fact.name(), read(fact, env)));
            }
            return List.<KSAnnotated>of();
        };
        KspCompiler.compile(List.of(probe), options, sources);
        if (results.isEmpty()) {
            throw new AssertionError("the probe never ran");
        }
        return results;
    }

    private static Object read(Fact fact, ProcessingEnv env) {
        try {
            return fact.read().apply(env);
        } catch (RuntimeException | AssertionError e) {
            return new Threw(e.getClass());
        }
    }

    static ClassDecl cls(ProcessingEnv env, String qualifiedName) {
        return env.types().findClass(qualifiedName).orElseThrow(() -> new AssertionError("no class " + qualifiedName));
    }

    static MethodDecl method(ProcessingEnv env, String className, String name) {
        return cls(env, className).methods().stream()
                .filter(method -> method.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + className + "." + name));
    }

    static FieldDecl field(ProcessingEnv env, String className, String name) {
        return cls(env, className).fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + className + "." + name));
    }
}
