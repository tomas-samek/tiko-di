package io.tiko.processor;

import static io.tiko.processor.CompileTesting.compile;
import static io.tiko.processor.CompileTesting.generated;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * #744: a component whose profile is not active is left out of the build with everything it
 * declares. Its {@code @EventHandler}s and {@code @Produces} methods used to stay in, and the
 * generated code then called a getter the container does not have.
 */
class InactiveProfileMembersTest {

    private static final String IMPORTS = "import io.tiko.Scope; import io.tiko.annotations.*;";

    private static final JavaFileObject ANCHOR =
            source("Anchor", "@Component(scope = Scope.SINGLETON)", "public class Anchor {}");

    private static final JavaFileObject THING = source("Thing", "public class Thing {}");

    private static final JavaFileObject DEV_ONLY_FACTORIES = source(
            "Cfg",
            "@Component(profiles = {\"dev\"})",
            "public class Cfg {",
            "  @Produces public Thing thing() { return new Thing(); }",
            "  @Produces public static String label() { return \"dev\"; }",
            "}");

    @Test
    void eventHandlerOfAnInactiveComponentIsLeftOutOfTheRegistry() throws IOException {
        Compilation c = compileForProd(
                ANCHOR,
                source(
                        "DevOnly",
                        "@Component(scope = Scope.SINGLETON, profiles = {\"dev\"})",
                        "public class DevOnly {",
                        "  @EventHandler public void on(String event) {}",
                        "}"),
                source(
                        "Always",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class Always {",
                        "  @EventHandler public void on(String event) {}",
                        "}"));

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "EventRegistry")).contains("Always").doesNotContain("DevOnly");
    }

    @Test
    void producesMethodsOfAnInactiveComponentAreLeftOutOfTheContainer() throws IOException {
        Compilation c = compileForProd(ANCHOR, THING, DEV_ONLY_FACTORIES);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "TikoContainerImpl")).doesNotContain("produce_Cfg_thing", "produce_Cfg_label");
    }

    @Test
    void consumerOfWhatOnlyAnInactiveComponentProducesGetsTheMissingDependencyError() {
        Compilation c = compileForProd(
                THING,
                DEV_ONLY_FACTORIES,
                source(
                        "User",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class User {",
                        "  @Inject public User(Thing thing) {}",
                        "}"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("Cannot resolve dependency: demo.Thing");
    }

    @Test
    void membersOfAnActiveProfileComponentStayIn() throws IOException {
        Compilation c = compile(List.of("-Atiko.profiles=dev"), ANCHOR, THING, DEV_ONLY_FACTORIES);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generated(c, "TikoContainerImpl")).contains("produce_Cfg_thing", "produce_Cfg_label");
    }

    @Test
    void profileNamesInTheOptionAreTrimmed() {
        assertThat(TikoPipeline.parseActiveProfiles(Map.of(TikoPipeline.PROFILES_OPTION, "dev, test ,prod")))
                .isEqualTo(List.of("dev", "test", "prod"));
    }

    private static Compilation compileForProd(JavaFileObject... sources) {
        return compile(List.of("-Atiko.profiles=prod"), sources);
    }

    private static JavaFileObject source(String name, String... lines) {
        return CompileTesting.source(IMPORTS, name, lines);
    }
}
