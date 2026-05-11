package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Validator tests for {@code @Pick(Class)}: the class-literal qualifier for
 * disambiguating between multiple {@code @Component} impls of the same interface.
 *
 * <p>Covers the intra-module rules from {@link PickValidator}:
 * <ol>
 *   <li>assignability of the picked class to the parameter type</li>
 *   <li>(cross-module manifest — separate test once that lands)</li>
 *   <li>redundant {@code @Pick} (picked class equals parameter type)</li>
 *   <li>{@code @Pick} on collection parameters</li>
 *   <li>ambiguous picked type produced by multiple {@code @Produces} methods —
 *       errors only when {@code @Named} is absent; combining {@code @Pick} with
 *       {@code @Named} is the supported way to disambiguate</li>
 * </ol>
 */
class PickValidatorTest {

    @Test
    void picking_one_of_two_component_impls_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        impl("PostgresDataSource"),
                        consumer(
                                "OrderService",
                                "@Pick(MySqlDataSource.class) DataSource primary, "
                                        + "@Pick(PostgresDataSource.class) DataSource analytics"));

        assertThat(compilation).succeeded();
    }

    @Test
    void rule1_picked_class_not_assignable_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        unrelated("StringHolder"),
                        consumer("OrderService", "@Pick(StringHolder.class) DataSource ds"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("not assignable to parameter type DataSource");
    }

    @Test
    void rule3_pick_references_parameter_type_itself_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        consumer("OrderService", "@Pick(DataSource.class) DataSource ds"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Pick references the parameter type itself");
    }

    @Test
    void pick_composed_with_named_resolves_one_of_two_producers_with_same_return_type() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        twoNamedProducersOfSameType(),
                        consumer("OrderService", "@Pick(MySqlDataSource.class) @Named(\"a\") DataSource ds"));

        assertThat(compilation).succeeded();
    }

    @Test
    void pick_composed_with_named_fails_when_no_producer_matches_the_pair() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        consumer("OrderService", "@Pick(MySqlDataSource.class) @Named(\"missing\") DataSource ds"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Cannot resolve dependency");
        assertThat(compilation).hadErrorContaining("MySqlDataSource#missing");
    }

    @Test
    void rule5_pick_on_set_collection_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        JavaFileObjects.forSourceLines(
                                "io.tiko.processor.fixtures.pick.OrderService",
                                "package io.tiko.processor.fixtures.pick;",
                                "",
                                "import io.tiko.Scope;",
                                "import io.tiko.annotations.Component;",
                                "import io.tiko.annotations.Inject;",
                                "import io.tiko.annotations.Pick;",
                                "import java.util.Set;",
                                "",
                                "@Component(scope = Scope.SINGLETON)",
                                "public class OrderService {",
                                "    @Inject",
                                "    public OrderService(@Pick(MySqlDataSource.class) Set<DataSource> sources) {}",
                                "}"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Pick cannot be applied to collection or iterable parameters");
    }

    @Test
    void rule6_two_produces_returning_same_type_makes_pick_ambiguous() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        twoNamedProducersOfSameType(),
                        consumer("OrderService", "@Pick(MySqlDataSource.class) DataSource ds"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("is ambiguous");
    }

    @Test
    void rule6_ambiguity_resolved_by_adding_named() {
        // Same fixture as rule6_two_produces… but the consumer adds @Named to pick one.
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        twoNamedProducersOfSameType(),
                        consumer("OrderService", "@Pick(MySqlDataSource.class) @Named(\"a\") DataSource ds"));

        assertThat(compilation).succeeded();
    }

    // -- fixture builders -------------------------------------------------------

    /** Interface that impls implement and consumers depend on. */
    private static JavaFileObject api(String simpleName) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.pick." + simpleName,
                "package io.tiko.processor.fixtures.pick;",
                "",
                "public interface " + simpleName + " {",
                "    String name();",
                "}");
    }

    /** A @Component impl of DataSource. */
    private static JavaFileObject impl(String simpleName) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.pick." + simpleName,
                "package io.tiko.processor.fixtures.pick;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + simpleName + " implements DataSource {",
                "    public String name() { return \"" + simpleName + "\"; }",
                "}");
    }

    /** A @Component class unrelated to DataSource (used for assignability negative tests). */
    private static JavaFileObject unrelated(String simpleName) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.pick." + simpleName,
                "package io.tiko.processor.fixtures.pick;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + simpleName + " {",
                "    public String value() { return \"\"; }",
                "}");
    }

    /**
     * Two @Produces methods on the same config class returning {@code MySqlDataSource}
     * with different names ({@code "a"} and {@code "b"}). Used both for the rule-6
     * ambiguity test and the "compose with @Named to resolve" test — same shape, the
     * difference is whether the consumer adds {@code @Named}.
     */
    private static JavaFileObject twoNamedProducersOfSameType() {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.pick.MoreSources",
                "package io.tiko.processor.fixtures.pick;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class MoreSources {",
                "    @Produces(scope = Scope.SINGLETON, name = \"a\")",
                "    public MySqlDataSource a() { return new MySqlDataSource(); }",
                "    @Produces(scope = Scope.SINGLETON, name = \"b\")",
                "    public MySqlDataSource b() { return new MySqlDataSource(); }",
                "}");
    }

    /** A consumer with a single @Inject constructor whose params are interpolated in. */
    private static JavaFileObject consumer(String simpleName, String constructorParams) {
        return JavaFileObjects.forSourceLines(
                "io.tiko.processor.fixtures.pick." + simpleName,
                "package io.tiko.processor.fixtures.pick;",
                "",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "import io.tiko.annotations.Named;",
                "import io.tiko.annotations.Pick;",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + simpleName + " {",
                "    @Inject",
                "    public " + simpleName + "(" + constructorParams + ") {}",
                "}");
    }
}
