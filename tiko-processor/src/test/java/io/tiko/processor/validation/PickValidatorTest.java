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
 *   <li>{@code @Pick} + {@code @Named} on one parameter</li>
 *   <li>{@code @Pick} on collection parameters</li>
 *   <li>ambiguous picked type produced by multiple {@code @Produces} methods</li>
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
    void rule4_pick_combined_with_named_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        api("DataSource"),
                        impl("MySqlDataSource"),
                        impl("PostgresDataSource"),
                        consumer("OrderService", "@Pick(MySqlDataSource.class) @Named(\"primary\") DataSource ds"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@Pick and @Named cannot be combined");
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
                        JavaFileObjects.forSourceLines(
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
                                "}"),
                        consumer("OrderService", "@Pick(MySqlDataSource.class) DataSource ds"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("is ambiguous");
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
